use uuid::Uuid;
use chrono::NaiveDate;
use serde::{Deserialize, Serialize};
use reqwest::StatusCode;
use crate::CatalogError::UnexpectedStatusCode;

pub struct CatalogConfig {
    pub url: String,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct Language {
    pub code: String,
    pub name: String,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct Country {
    pub code: String,
    pub name: String,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct Genre {
    pub id: Uuid,
    pub name: String,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct Movie {
    pub id: Uuid,
    pub title: String,
    pub tagline: Option<String>,
    pub overview: Option<String>,
    pub spoken_languages: Vec<Language>,
    pub production_countries: Vec<Country>,
    pub genres: Vec<Genre>,
    pub release_date: Option<NaiveDate>,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct CreateMovieRequest {
    pub title: String,
    pub tagline: Option<String>,
    pub overview: Option<String>,
    pub spoken_languages: Option<Vec<Language>>,
    pub production_countries: Option<Vec<Country>>,
    pub genres: Option<Vec<Genre>>,
    pub release_date: Option<NaiveDate>,
}

#[derive(Error, Debug)]
pub enum CatalogError {
    #[error("error calling server: {0}")]
    ClientError(#[from] actix_web::error::Error),
    UnexpectedStatusCode(StatusCode),
}

pub async fn get_movie(
    cfg: &CatalogConfig,
    id: Uuid,
) -> Result<Option<Movie>, CatalogError> {
    let res = reqwest::Client::new()
        .get(format!("{}/catalog/movies/v1/{}", cfg.url, id))
        .header("Accept", "application/json")
        .send()
        .await?;

    match res.status() {
        StatusCode::OK => {
            let m: Movie = res.json();
            Ok(Some(m))
        }
        StatusCode::NOT_FOUND => Ok(None),
        unexpected => Err(UnexpectedStatusCode(unexpected))
    }
}

pub async fn create_movie(
    cfg: &CatalogConfig,
    req: CreateMovieRequest,
) -> Result<Movie, CatalogError> {
    let res = reqwest::Client::new()
        .post(format!("{}/catalog/movies/v1", cfg.url))
        .json(&req)
        .header("Accept", "application/json")
        .send()
        .await?;

    match res.status() {
        StatusCode::CREATED => {
            let m: Movie = res.json();
            Ok(m)
        }
        unexpected => Err(UnexpectedStatusCode(unexpected))
    }
}


#[cfg(test)]
mod tests {
    #[test]
    fn it_works() {
        assert_eq!(2 + 2, 4);
    }
}
