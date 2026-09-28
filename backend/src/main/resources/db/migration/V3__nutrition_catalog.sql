CREATE TABLE nutrition_datasets (
 id VARCHAR(64) PRIMARY KEY, filename VARCHAR(255) NOT NULL, encoding VARCHAR(30) NOT NULL,
 row_count INT NOT NULL, imported_at BIGINT NOT NULL
);
ALTER TABLE foods ADD COLUMN category VARCHAR(40) NOT NULL DEFAULT 'legacy';
ALTER TABLE foods ADD COLUMN original_code VARCHAR(100);
ALTER TABLE foods ADD COLUMN manufacturer VARCHAR(500);
ALTER TABLE foods ADD COLUMN current_version_id VARCHAR(64);
ALTER TABLE foods ADD COLUMN searchable BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE foods ADD COLUMN basis_unit VARCHAR(10) NOT NULL DEFAULT 'g';
CREATE TABLE food_nutrition_versions (
 id VARCHAR(64) PRIMARY KEY, food_id VARCHAR(100) NOT NULL,
 name VARCHAR(200) NOT NULL, basis_amount DECIMAL(12,3), basis_unit VARCHAR(10),
 kcal DECIMAL(12,3), carbs DECIMAL(12,3), protein DECIMAL(12,3), fat DECIMAL(12,3),
 source VARCHAR(500) NOT NULL, source_date VARCHAR(100) NOT NULL,
 raw_basis VARCHAR(200) NOT NULL,
 FOREIGN KEY(food_id) REFERENCES foods(id)
);
CREATE TABLE nutrition_source_rows (
 dataset_id VARCHAR(64) NOT NULL, row_number INT NOT NULL, version_id VARCHAR(64) NOT NULL,
 raw_json TEXT NOT NULL,
 PRIMARY KEY(dataset_id,row_number),
 FOREIGN KEY(dataset_id) REFERENCES nutrition_datasets(id),
 FOREIGN KEY(version_id) REFERENCES food_nutrition_versions(id)
);
CREATE TABLE food_aliases (
 food_id VARCHAR(100) NOT NULL, alias VARCHAR(200) NOT NULL,
 PRIMARY KEY(food_id,alias), FOREIGN KEY(food_id) REFERENCES foods(id)
);
CREATE TABLE food_portions (
 version_id VARCHAR(64) NOT NULL, label VARCHAR(100) NOT NULL,
 amount DECIMAL(12,3) NOT NULL, unit VARCHAR(10) NOT NULL,
 PRIMARY KEY(version_id,label), FOREIGN KEY(version_id) REFERENCES food_nutrition_versions(id)
);
CREATE INDEX food_catalog_search ON foods(searchable,category);
CREATE INDEX nutrition_version_food ON food_nutrition_versions(food_id);
