CREATE TYPE genre AS (
    id UUID,
    label TEXT
);

CREATE TYPE language AS (
    code TEXT,
    label TEXT
);

CREATE TYPE country AS (
    code TEXT,
    label TEXT
);

CREATE TABLE movies (
   id UUID PRIMARY KEY,
   title TEXT NOT NULL,
   tagline TEXT NULL,
   overview TEXT NULL,
   spoken_languages language[] NOT NULL DEFAULT '{}'::language[],
   production_countries country[] NOT NULL DEFAULT '{}'::country[],
   genres genre[] NOT NULL DEFAULT '{}'::genre[],
   release_date DATE
);