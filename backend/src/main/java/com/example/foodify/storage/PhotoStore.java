package com.example.foodify.storage;
import com.example.foodify.api.ApiError;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.UUID;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.core.sync.RequestBody;

@Component
public class PhotoStore {
    private final Path root;
    private final S3Client s3;
    private final String bucket;
    public PhotoStore(@Value("${app.photos}") String directory,@Value("${app.storage:local}") String mode,@Value("${app.s3.bucket:}") String bucket,@Value("${app.s3.region:ap-northeast-2}") String region) throws IOException {
        root=Path.of(directory).toAbsolutePath().normalize(); this.bucket=bucket;
        if("s3".equals(mode)) {if(bucket.isBlank()) throw new IllegalArgumentException("S3 bucket required");s3=S3Client.builder().region(Region.of(region)).build();}
        else {s3=null;Files.createDirectories(root);}
    }
    public String save(MultipartFile file) throws IOException {
        if(file.isEmpty() || file.getSize()>8*1024*1024) throw ApiError.bad("IMAGE_SIZE");
        BufferedImage source;
        try(var stream=ImageIO.createImageInputStream(file.getInputStream())) {
            var readers=ImageIO.getImageReaders(stream);
            if(!readers.hasNext()) throw ApiError.bad("UNSUPPORTED_IMAGE");
            var reader=readers.next();
            try {
                reader.setInput(stream);
                if((long)reader.getWidth(0)*reader.getHeight(0)>25000000) throw ApiError.bad("IMAGE_DIMENSIONS");
                source=reader.read(0);
            } finally { reader.dispose(); }
        }
        double factor=Math.min(1,1600.0/Math.max(source.getWidth(),source.getHeight()));
        var clean=new BufferedImage(Math.max(1,(int)(source.getWidth()*factor)),Math.max(1,(int)(source.getHeight()*factor)),BufferedImage.TYPE_INT_RGB);
        Graphics2D g=clean.createGraphics(); g.setColor(Color.WHITE);g.fillRect(0,0,clean.getWidth(),clean.getHeight());
        g.drawImage(source,0,0,clean.getWidth(),clean.getHeight(),null);g.dispose();
        String key=UUID.randomUUID()+".jpg";
        // Re-encode pixels only; EXIF/location metadata is discarded.
        if(s3==null) ImageIO.write(clean,"jpg",path(key).toFile());
        else {var bytes=new ByteArrayOutputStream();ImageIO.write(clean,"jpg",bytes);s3.putObject(b->b.bucket(bucket).key(key).contentType("image/jpeg"),RequestBody.fromBytes(bytes.toByteArray()));}
        return key;
    }
    private Path path(String key) {
        if(!key.matches("[a-f0-9-]{36}\\.jpg")) throw ApiError.bad("INVALID_PHOTO_KEY");
        Path p=root.resolve(key).normalize(); if(!p.startsWith(root)) throw ApiError.bad("INVALID_PHOTO_KEY"); return p;
    }
    public byte[] read(String key) throws IOException {path(key);return s3==null?Files.readAllBytes(path(key)):s3.getObjectAsBytes(b->b.bucket(bucket).key(key)).asByteArray();}
    public void delete(String key) throws IOException {path(key);if(s3==null)Files.deleteIfExists(path(key));else s3.deleteObject(b->b.bucket(bucket).key(key));}
}
