CREATE INDEX title_release_date_idx ON movies(
    title ASC,
    release_date DESC NULLS LAST,
    id ASC
);

CREATE INDEX release_date_title_idx ON movies(
    release_date DESC NULLS LAST,
    title ASC,
    id ASC
);