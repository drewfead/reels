table! {
    movies (id) {
        id -> Uuid,
        title -> Text,
        tagline -> Nullable<Text>,
        overview -> Nullable<Text>,
        spoken_languages -> Array<crate::db::types::PgLanguage>,
        production_countries -> Array<crate::db::types::PgCountry>,
        genres -> Array<crate::db::types::PgGenre>,
        release_date -> Nullable<Date>,
        created -> Timestamp,
        updated -> Timestamp,
        indexed -> Nullable<Timestamp>,
        foreign_url -> Nullable<Text>,
    }
}
