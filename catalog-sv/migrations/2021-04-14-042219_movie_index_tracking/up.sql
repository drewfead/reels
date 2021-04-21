ALTER TABLE movies
ADD COLUMN indexed TIMESTAMP NULL;

CREATE INDEX indexed_and_updated_idx ON movies(
    indexed ASC NULLS FIRST,
    updated ASC,
    id ASC
);