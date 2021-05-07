use actix_web::{delete, Error, get, HttpResponse, post, put, Responder, web};
use actix_web::error::BlockingError;
use actix_web::web::Json;
use futures::TryFutureExt;
use log::error;
use serde::{Deserialize, Serialize};
use uuid::Uuid;

use crate::core::{CreateMovieParams, HasId, Movie, Page, PaginationParameters, UpdateMovieParams};
use crate::core::action;
use crate::db::DbConnection;
use crate::db::DbConnectionPool;
use crate::idx::IndexClient;
use std::collections::HashMap;
use either::Either::{Left, Right};
use either::Either;
use percent_encoding::{utf8_percent_encode, NON_ALPHANUMERIC};

#[get("/health")]
pub async fn health() -> impl Responder {
    HttpResponse::Ok()
}

#[post("/movies/v1")]
pub async fn post_movie(
    pool: web::Data<DbConnectionPool>,
    req: Json<CreateMovieParams>,
) -> Result<HttpResponse, Error> {
    let conn: DbConnection = pool.get()
        .expect("couldn't get db connection from pool");

    let movie = web::block(move || action::create_movie(&conn, req.into_inner()))
        .await
        .map_err(|e| {
            error!("{}", e);
            HttpResponse::InternalServerError().finish()
        })?;

    match movie {
        Left(id) => Ok(HttpResponse::Conflict().json(id)),
        Right(m) => Ok(HttpResponse::Created().json(m))
    }
}

#[get("/movies/v1/{movie_id}")]
pub async fn get_movie(
    pool: web::Data<DbConnectionPool>,
    movie_id: web::Path<Uuid>,
) -> Result<HttpResponse, Error> {
    let conn: DbConnection = pool.get()
        .expect("couldn't get db connection from pool");

    let maybe = web::block(move || action::find_one_movie(&conn, movie_id.into_inner()))
        .await
        .map_err(|e| {
            error!("{}", e);
            HttpResponse::InternalServerError().finish()
        })?;

    match maybe {
        None => Ok(HttpResponse::NotFound().finish()),
        Some(movie) => Ok(HttpResponse::Ok().json(movie))
    }
}

#[put("/movies/v1/{movie_id}")]
pub async fn put_movie(
    pool: web::Data<DbConnectionPool>,
    movie_id: web::Path<Uuid>,
    req: Json<UpdateMovieParams>,
) -> Result<HttpResponse, Error> {
    let conn: DbConnection = pool.get()
        .expect("couldn't get db connection from pool");

    let updated = web::block(move || action::update_movie(&conn, movie_id.into_inner(), req.into_inner()))
        .await
        .map_err(|e| {
            error!("{}", e);
            HttpResponse::InternalServerError().finish()
        })?;

    match updated {
        None => Ok(HttpResponse::NotFound().finish()),
        Some(movie) => Ok(HttpResponse::Ok().json(movie))
    }
}

#[delete("/movies/v1/{movie_id}")]
pub async fn delete_movie(
    pool: web::Data<DbConnectionPool>,
    movie_id: web::Path<Uuid>,
) -> Result<HttpResponse, Error> {
    let conn: DbConnection = pool.get()
        .expect("couldn't get db connection from pool");

    let was_present = web::block(move || action::delete_movie(&conn, movie_id.into_inner()))
        .await
        .map_err(|e| {
            error!("{}", e);
            HttpResponse::InternalServerError().finish()
        })?;

    if was_present {
        Ok(HttpResponse::NoContent().finish())
    } else {
        Ok(HttpResponse::NotFound().finish())
    }
}

#[derive(Serialize)]
pub struct QueryResponse {
    pub items: Vec<Movie>,
    pub next_page: Option<String>,
}

impl QueryResponse {
    fn from_page(
        page: Page<Movie>,
        count: Option<i64>,
        search_term: Option<String>,
        base_url: String,
    ) -> QueryResponse {
        let query_parts: Vec<String> = vec![
            count.map(|c| format!("count={}", c)),
            search_term
                .map(|s| format!("search={}", utf8_percent_encode(&s, NON_ALPHANUMERIC))),
            page.next_anchor.as_ref()
                .map(|a| format!("anchor={}", a)),
        ].into_iter().flatten().collect();

        let q_string = if query_parts.is_empty() {
            "".to_string()
        } else {
            format!("?{}", query_parts.join("&"))
        };

        QueryResponse {
            items: page.items,
            next_page: page.next_anchor.map(|_| format!("{}{}", base_url, q_string))
        }
    }
}

#[derive(Clone, Deserialize)]
pub struct Query {
    pub search: Option<String>,
}

fn backfill_unresolved_movies(
    conn: &DbConnection,
    found: Page<Either<HasId, Movie>>,
) -> Result<Page<Movie>, crate::core::error::Error> {
    let unresolved_ids = found.items.iter()
        .filter_map(|e| match e {
            Left(id) => Some(id.id),
            _ => None,
        })
        .collect();

    let extra: HashMap<Uuid, Movie> = action::find_movies_with_ids(conn, unresolved_ids)?
        .items
        .into_iter()
        .map(|m| (m.id, m))
        .collect();

    Ok(Page {
        items: found.items.into_iter()
            .filter_map(|e| match e {
                Left(id) => extra.get(&id.id).cloned(),
                Right(m) => Some(m)
            })
            .collect(),
        next_anchor: found.next_anchor,
        page_number: found.page_number,
    })
}

#[get("/movies/v1")]
pub async fn get_movies(
    req: web::HttpRequest,
    pool: web::Data<DbConnectionPool>,
    client: web::Data<IndexClient>,
    query: web::Query<Query>,
    pagination: web::Query<PaginationParameters>,
) -> Result<HttpResponse, Error> {
    let p = pagination.into_inner();
    let q = query.into_inner();

    let count: i64 = p.count.unwrap_or_else(|| 25);
    let anchor = p.anchor.clone();

    let conn: DbConnection = pool.get()
        .expect("couldn't get db connection from pool");

    let action = match &q {
        Query { search: Some(search_term) } =>
            action::search_movies(&client, search_term, count, &anchor)
                .map_err(BlockingError::Error)
                .await,
        _ =>
            web::block(move || action::find_movies(&conn, count, &anchor))
                .await
                .map(|found|
                    Page {
                        page_number: found.page_number,
                        next_anchor: found.next_anchor,
                        items: found.items.into_iter().map(|m| Right(m)).collect(),
                    }
                )
    };

    let next = match action? {
        all_found if all_found.items.iter().all(|e| e.is_right()) =>
            Ok(Page {
                page_number: all_found.page_number,
                next_anchor: all_found.next_anchor,
                items: all_found.items.into_iter()
                    .map(|e| e.unwrap_right())
                    .collect()
            }),
        backfill => {
            let conn = pool.get()
                .expect("couldn't get db connection from pool");

            web::block(move || backfill_unresolved_movies(&conn, backfill))
                .await
        }
    };

    let movies = next
        .map(|r| QueryResponse::from_page(r, p.count, q.search, req.path().to_string()))
        .map_err(|e| {
            error!("{}", e);
            HttpResponse::InternalServerError().finish()
        })?;

    Ok(HttpResponse::Ok().json(movies))
}