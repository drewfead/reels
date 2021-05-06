use chrono::{NaiveDate, Utc, DateTime};
use diesel::{AsChangeset, AsExpression, FromSqlRow, Identifiable, Insertable, Queryable};
use serde::{Deserialize, Serialize};
use uuid::Uuid;

use crate::db::schema::movies;

pub mod action;
pub mod error;

#[derive(Clone, Serialize, Deserialize, Debug)]
pub struct PaginationParameters {
    pub count: Option<i64>,
    pub anchor: Option<String>,
}

#[derive(Clone, Serialize, Deserialize, Debug)]
pub struct Page<T> {
    pub page_number: i64,
    pub next_anchor: Option<String>,
    pub items: Vec<T>,
}

#[derive(Clone, Serialize, Deserialize, FromSqlRow, AsExpression, PartialEq, Debug)]
#[serde(rename_all = "camelCase")]
#[sql_type="crate::db::types::PgLanguage"]
pub struct Language {
    pub code: String,
    pub name: String,
}

#[derive(Clone, Serialize, Deserialize, FromSqlRow, AsExpression, PartialEq, Debug)]
#[serde(rename_all = "camelCase")]
#[sql_type="crate::db::types::PgCountry"]
pub struct Country {
    pub code: String,
    pub name: String,
}

#[derive(Clone, Serialize, Deserialize, FromSqlRow, AsExpression, PartialEq, Debug)]
#[serde(rename_all = "camelCase")]
#[sql_type="crate::db::types::PgGenre"]
pub struct Genre {
    #[serde(default = "uuid::Uuid::new_v4")]
    pub id: Uuid,
    pub name: String,
}

#[derive(Clone, Debug, Serialize, Identifiable, Insertable, Queryable)]
#[serde(rename_all = "camelCase")]
#[table_name="movies"]
pub struct Movie {
    pub id: Uuid,
    pub title: String,
    pub tagline: Option<String>,
    pub overview: Option<String>,
    pub spoken_languages: Vec<Language>,
    pub production_countries: Vec<Country>,
    pub genres: Vec<Genre>,
    pub release_date: Option<NaiveDate>,
    pub created: DateTime<Utc>,
    pub updated: DateTime<Utc>,
    pub indexed: Option<DateTime<Utc>>,
    pub foreign_url: Option<String>,
    pub deleted: Option<DateTime<Utc>>,
}

#[derive(Clone, Debug, AsChangeset)]
#[table_name="movies"]
pub struct MovieChangeset {
    pub title: Option<String>,
    pub tagline: Option<Option<String>>,
    pub overview: Option<Option<String>>,
    pub spoken_languages: Option<Vec<Language>>,
    pub production_countries: Option<Vec<Country>>,
    pub genres: Option<Vec<Genre>>,
    pub release_date: Option<Option<NaiveDate>>,
    pub updated: Option<DateTime<Utc>>,
    pub indexed: Option<DateTime<Utc>>,
    pub foreign_url: Option<Option<String>>,
    pub deleted: Option<DateTime<Utc>>,
}

#[derive(Clone, Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CreateMovieParams {
    pub title: String,
    pub tagline: Option<String>,
    pub overview: Option<String>,
    #[serde(default = "Vec::new")]
    pub spoken_languages: Vec<Language>,
    #[serde(default = "Vec::new")]
    pub production_countries: Vec<Country>,
    #[serde(default = "Vec::new")]
    pub genres: Vec<Genre>,
    pub release_date: Option<NaiveDate>,
    pub foreign_url: Option<String>,
}

impl CreateMovieParams {
    fn create(&self) -> Movie {
        let now = Utc::now();
        let id_namespace = Uuid::new_v5(&Uuid::nil(), format!("{:?}", &self.release_date).as_bytes());
        let id_name = format!("{:?}", &self.title).into_bytes();
        let id = Uuid::new_v5(&id_namespace, &id_name);

        Movie {
            id,
            title: self.title.clone(),
            tagline: self.tagline.clone(),
            overview: self.overview.clone(),
            spoken_languages: self.spoken_languages.clone(),
            production_countries: self.production_countries.clone(),
            genres: self.genres.clone(),
            release_date: self.release_date.clone(),
            created: now,
            updated: now,
            indexed: None,
            foreign_url: self.foreign_url.clone(),
            deleted: None,
        }
    }
}

#[derive(Clone, Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct UpdateMovieParams {
    pub title: Option<String>,
    pub tagline: Option<Option<String>>,
    pub overview: Option<Option<String>>,
    pub spoken_languages: Option<Vec<Language>>,
    pub production_countries: Option<Vec<Country>>,
    pub genres: Option<Vec<Genre>>,
    pub release_date: Option<Option<NaiveDate>>,
    pub foreign_url: Option<Option<String>>,
}

impl UpdateMovieParams {
    fn update(&self) -> MovieChangeset {
        let now = Utc::now();
        MovieChangeset {
            title: self.title.clone(),
            tagline: self.tagline.clone(),
            overview: self.overview.clone(),
            spoken_languages: self.spoken_languages.clone(),
            production_countries: self.production_countries.clone(),
            genres: self.genres.clone(),
            release_date: self.release_date.clone(),
            updated: Some(now),
            indexed: None,
            foreign_url: self.foreign_url.clone(),
            deleted: None,
        }
    }
}

#[derive(Clone, Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct IndexMovie;

impl IndexMovie {
    fn update(&self) -> MovieChangeset {
        let now = Utc::now();
        MovieChangeset {
            title: None,
            tagline: None,
            overview: None,
            spoken_languages: None,
            production_countries: None,
            genres: None,
            release_date: None,
            updated: None,
            indexed: Some(now),
            foreign_url: None,
            deleted: None,
        }
    }
}

#[derive(Clone, Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DeleteMovie;

impl DeleteMovie {
    fn update(&self) -> MovieChangeset {
        let now = Utc::now();
        MovieChangeset {
            title: None,
            tagline: None,
            overview: None,
            spoken_languages: None,
            production_countries: None,
            genres: None,
            release_date: None,
            updated: Some(now),
            indexed: None,
            foreign_url: None,
            deleted: Some(now),
        }
    }
}