package com.example.foodify.ar

/**
 * Pure parts of MobileSAM on the device: input preprocessing, box coordinates and mask -> outline polygons.
 * Matches SamPredictor (ResizeLongestSide 1024, ImageNet-style pixel mean/std, zero padding at the
 * bottom/right); checked against PyTorch on the M12 records (mask IoU 0.85~0.997, 2026-11-10).
 */
object SamGeometry {
    const val SIZE=1024
    private val MEAN=floatArrayOf(123.675f,116.28f,103.53f)
    private val STD=floatArrayOf(58.395f,57.12f,57.375f)
    /** Pieces smaller than this share of the photo are dropped (crumbs, noise). */
    const val MIN_PIECE_FRACTION=0.0004
    /** Same limit as ArVolume.validate and the app region format. */
    const val MAX_PIECES=30

    fun scale(width:Int,height:Int)=SIZE.toDouble()/maxOf(width,height)
    fun resized(width:Int,height:Int):Pair<Int,Int> {
        val s=scale(width,height);return Pair((width*s+.5).toInt(),(height*s+.5).toInt())
    }
    /** ARGB pixels of the resized photo (width x height) -> CHW floats of 3 x 1024 x 1024, zero-padded. */
    fun input(pixels:IntArray,width:Int,height:Int):FloatArray {
        require(width in 1..SIZE && height in 1..SIZE && pixels.size==width*height)
        val out=FloatArray(3*SIZE*SIZE)
        for(y in 0 until height) for(x in 0 until width) {
            val p=pixels[y*width+x];val i=y*SIZE+x
            out[i]=(((p shr 16) and 255)-MEAN[0])/STD[0]
            out[SIZE*SIZE+i]=(((p shr 8) and 255)-MEAN[1])/STD[1]
            out[2*SIZE*SIZE+i]=((p and 255)-MEAN[2])/STD[2]
        }
        return out
    }
    /** Normalized box (x1,y1,x2,y2) on the original photo -> the two box corners in model input pixels. */
    fun boxCoords(box:DoubleArray,width:Int,height:Int):FloatArray {
        require(box.size==4 && box.all { it.isFinite() && it in 0.0..1.0 } && box[2]>box[0] && box[3]>box[1])
        val s=scale(width,height)
        return floatArrayOf((box[0]*width*s).toFloat(),(box[1]*height*s).toFloat(),(box[2]*width*s).toFloat(),(box[3]*height*s).toFloat())
    }
    /** Most tap points per segmentation request. */
    const val MAX_POINTS=20
    /**
     * Decoder prompt from user taps (normalized u, v; label 1 = food, 0 = not food) and an optional box.
     * Without a box SAM needs a padding point (0,0) with label -1. Returns (coords in model input pixels, labels).
     */
    fun prompt(points:List<Triple<Double,Double,Int>>,box:DoubleArray?,width:Int,height:Int):Pair<FloatArray,FloatArray> {
        require(points.size<=MAX_POINTS && (points.isNotEmpty() || box!=null)) { "음식을 한 곳 이상 눌러 주세요." }
        require(points.all { it.first.isFinite() && it.second.isFinite() && it.first in 0.0..1.0 && it.second in 0.0..1.0 && it.third in 0..1 }) { "누른 위치를 확인해 주세요." }
        require(points.any { it.third==1 } || box!=null) { "음식 부분을 한 곳 이상 눌러 주세요." }
        val s=scale(width,height)
        val coords=ArrayList<Float>();val labels=ArrayList<Float>()
        for((u,v,label) in points) {coords.add((u*width*s).toFloat());coords.add((v*height*s).toFloat());labels.add(label.toFloat())}
        if(box!=null) {coords.addAll(boxCoords(box,width,height).toList());labels.add(2f);labels.add(3f)}
        else {coords.add(0f);coords.add(0f);labels.add(-1f)}
        return Pair(coords.toFloatArray(),labels.toFloatArray())
    }
    /**
     * Mask (row-major, original photo size) -> one convex outline per connected piece, image-normalized.
     * Separate pieces stay separate, so gaps between scattered food are not counted as food. Largest
     * pieces first, at most [MAX_PIECES]; pieces under [MIN_PIECE_FRACTION] of the photo are dropped.
     */
    fun polygons(mask:BooleanArray,width:Int,height:Int):List<List<Pair<Double,Double>>> {
        require(mask.size==width*height)
        val label=IntArray(mask.size) { -1 };val pieces=mutableListOf<IntArray>()
        val stack=IntArray(mask.size)
        for(start in mask.indices) {
            if(!mask[start] || label[start]>=0) continue
            val id=pieces.size;var top=0;stack[top++]=start;label[start]=id
            val members=ArrayList<Int>()
            while(top>0) {
                val i=stack[--top];members.add(i)
                val x=i%width;val y=i/width
                for((nx,ny) in arrayOf(x-1 to y,x+1 to y,x to y-1,x to y+1)) {
                    if(nx !in 0 until width || ny !in 0 until height) continue
                    val j=ny*width+nx
                    if(mask[j] && label[j]<0) {label[j]=id;stack[top++]=j}
                }
            }
            pieces.add(members.toIntArray())
        }
        val minPixels=maxOf(1,(MIN_PIECE_FRACTION*width*height).toInt())
        return pieces.filter { it.size>=minPixels }.sortedByDescending { it.size }.take(MAX_PIECES).mapNotNull { piece ->
            val corners=ArrayList<Pair<Double,Double>>()
            for(i in piece) {
                val x=i%width;val y=i/width
                // Only boundary pixels can be hull vertices.
                val inside=x>0 && x<width-1 && y>0 && y<height-1 && mask[i-1] && mask[i+1] && mask[i-width] && mask[i+width]
                if(inside) continue
                for((cx,cy) in arrayOf(x to y,x+1 to y,x to y+1,x+1 to y+1)) corners.add(Pair(cx.toDouble()/width,cy.toDouble()/height))
            }
            ArVolume.convexHull(corners).takeIf { it.size>=3 }
        }
    }
}
