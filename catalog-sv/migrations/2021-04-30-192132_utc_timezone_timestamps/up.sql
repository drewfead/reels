ALTER TABLE movies
ALTER COLUMN created TYPE timestamptz USING created AT TIME ZONE 'UTC',
ALTER COLUMN updated TYPE timestamptz USING updated AT TIME ZONE 'UTC',
ALTER COLUMN indexed TYPE timestamptz USING indexed AT TIME ZONE 'UTC',
ALTER COLUMN deleted TYPE timestamptz USING deleted AT TIME ZONE 'UTC';
