use base64::URL_SAFE_NO_PAD;
use chrono::NaiveDate;
use diesel::pg::PgConnection;
use diesel::prelude::*;
use diesel::r2d2::{ConnectionManager, PooledConnection};
use either::Either;
use either::Either::{Left, Right};
use log::debug;
use r2d2::Pool;
use serde::{Deserialize, Serialize};
use uuid::Uuid;

use crate::core::{HasId, Movie, MovieChangeset, Page};
use crate::core::error::Error;
use crate::core::error::Error::{AnchorDecodeError, AnchorParseError, DBQueryError};
use crate::db::pagination::*;

pub mod schema;
pub mod types;
mod pagination;

pub type DbConnection = PooledConnection<ConnectionManager<PgConnection>>;
pub type DbConnectionPool = Pool<ConnectionManager<PgConnection>>;

// todo: extract all this pagination stuff into a lib and clean it tf up
#[derive(Clone, Debug, Deserialize, Serialize)]
struct MovieAnchor {
    title: String,
    release_date: Option<NaiveDate>,
    id: Uuid,
    page_number: i64,
}

fn deserialize_anchor(raw: String) -> Result<MovieAnchor, Error> {
    let octets = base64::decode_config(raw, URL_SAFE_NO_PAD)
        .map_err(AnchorDecodeError)?;

    rmp_serde::from_read_ref(octets.as_slice())
        .map_err(AnchorParseError)
}

fn serialize_anchor(anch: MovieAnchor) -> String {
    let d = rmp_serde::to_vec(&anch).unwrap();
    base64::encode_config(d, URL_SAFE_NO_PAD)
}

pub fn find_movies(conn: &DbConnection, page_size: i64, anchor: &Option<String>) -> Result<Page<Movie>, Error> {

    use schema::movies::dsl::*;

    match anchor {
        None => {
            let page_number = 1;

            let query = movies
                .filter(deleted.is_null())
                .order((title.asc(), release_date.desc().nulls_last()))
                .count_remaining(page_size);

            debug!("{}", diesel::debug_query(&query));

            let (items, c) = query
                .load_and_count_remaining::<Movie>(conn)?;

            debug!("found items {:?} remaining={}", items, c);

            let next_anchor = if !items.is_empty() && c > 0 {
                let last = items.last().unwrap();
                let a = MovieAnchor {
                    title: last.title.clone(),
                    release_date: last.release_date.clone(),
                    id: last.id.clone(),
                    page_number: page_number + 1,
                };
                debug!("anchor generated {:?}", a);
                Some(serialize_anchor(a))
            } else {
                None
            };

            debug!("serialized_anchor={:?}", next_anchor);

            Ok(Page {
                page_number,
                next_anchor,
                items,
            })
        }

        Some(a) => {
            let anch = deserialize_anchor(a.to_string())?;

            debug!("deserialized anchor {:?}", anch);

            let filtered = movies.filter(title.gt(&anch.title));

            let next_q = match anch.release_date {
                Some(r) =>
                    filtered.or_filter(
                        title.eq(&anch.title)
                            .and(release_date.lt(r)
                                .or(release_date.eq(r)
                                    .and(id.gt(&anch.id))
                                )
                            )
                    ).into_boxed(),
                None =>
                    filtered.or_filter(
                        title.eq(&anch.title)
                            .and(release_date.is_null()
                                .and(id.gt(&anch.id))
                            )
                    ).into_boxed()
            };

            let query = next_q
                .filter(deleted.is_null())
                .order((title.asc(), release_date.desc().nulls_last()))
                .count_remaining(page_size);

            debug!("{}", diesel::debug_query(&query));

            let (items, c) = query
                .load_and_count_remaining::<Movie>(conn)?;

            debug!("found items {:?} remaining={}", items, c);

            let next_anchor = if !items.is_empty() && c > 0 {
                let last = items.last().unwrap();

                Some(serialize_anchor(MovieAnchor {
                    title: last.title.clone(),
                    release_date: last.release_date.clone(),
                    id: last.id.clone(),
                    page_number: anch.page_number + 1,
                }))
            } else {
                None
            };

            Ok(Page {
                page_number: anch.page_number,
                next_anchor,
                items,
            })
        }
    }
}

pub fn find_one_movie(conn: &DbConnection, movie_id: Uuid) -> Result<Option<Movie>, Error> {
    use schema::movies::dsl::*;

    movies.filter(id.eq(movie_id))
        .filter(deleted.is_null())
        .first(conn)
        .optional()
        .map_err(DBQueryError)
}

pub fn find_movies_with_ids(conn: &DbConnection, movie_ids: Vec<Uuid>) -> Result<Page<Movie>, Error> {
    use schema::movies::dsl::*;

    let items: Vec<Movie> = movies.filter(id.eq_any(movie_ids))
        .filter(deleted.is_null())
        .load(conn)
        .map_err(DBQueryError)?;

    Ok(Page {
        page_number: 1,
        next_anchor: None,
        items,
    })
}

pub fn create_movie(conn: &DbConnection, movie: Movie) -> Result<Either<HasId, Movie>, Error> {
    use schema::movies;
    use schema::movies::dsl::*;

    let query = diesel::insert_into(movies::table)
        .values(&movie)
        .on_conflict(id)
        // todo: add extension to support where clause on conflict updates
        // .do_update()
        // .set(&movie)
        // .filter(deleted.is_not_null())
        .do_nothing();

    debug!("{}", diesel::debug_query(&query));

    let option: Option<Movie> = query
        .get_result(conn)
        .optional()
        .map_err(DBQueryError)?;

    Ok(option.map_or(Left(HasId {
        id: movie.id.clone()
    }), |r| Right(r)))
}

pub fn update_movie(conn: &DbConnection, movie_id: Uuid, movie: MovieChangeset) -> Result<Option<Movie>, Error> {
    use schema::movies;
    use schema::movies::dsl::*;

    let query = diesel::update(movies::table)
        .set(&movie)
        .filter(id.eq(&movie_id))
        .filter(deleted.is_null());

    debug!("{}", diesel::debug_query(&query));

    query
        .get_result(conn)
        .optional()
        .map_err(DBQueryError)
}

pub fn update_movies(conn: &DbConnection, movie_ids: Vec<Uuid>, movie: MovieChangeset) -> Result<Vec<Movie>, Error> {
    use schema::movies;
    use schema::movies::dsl::*;

    if movie_ids.is_empty() {
        return Ok(Vec::new())
    }

    let query = diesel::update(movies::table)
        .set(&movie)
        .filter(id.eq_any(&movie_ids));

    debug!("{}", diesel::debug_query(&query));

    query
        .get_results(conn)
        .map_err(DBQueryError)
}

pub fn delete_movie(conn: &DbConnection, movie_id: Uuid) -> Result<bool, Error> {
    use schema::movies;
    use schema::movies::dsl::*;

    let query = diesel::delete(movies::table)
        .filter(id.eq(movie_id))
        .filter(deleted.is_null());

    debug!("{}", diesel::debug_query(&query));

    query
        .execute(conn)
        .map_err(DBQueryError)
        .map(|r| r > 0)
}

pub fn delete_soft_deleted(conn: &DbConnection) -> Result<usize, Error> {
    use schema::movies::dsl::*;

    let query = diesel::delete(schema::movies::table)
        .filter(deleted.is_not_null()
            .and(indexed.is_not_null())
            .and(deleted.lt(indexed)))
        .into_boxed::<diesel::pg::Pg>();

    debug!("{}", diesel::debug_query(&query));

    query
        .execute(conn)
        .map_err(DBQueryError)
}

pub fn find_stale_indexed(conn: &DbConnection, page_size: i64) -> Result<Vec<Movie>, Error> {
    use schema::movies::dsl::*;

    let query = movies
        .filter(indexed.is_null().or(indexed.lt(updated.nullable())))
        .order(indexed.asc().nulls_first())
        .limit(page_size);

    debug!("{}", diesel::debug_query(&query));

    query
        .get_results(conn)
        .map_err(DBQueryError)
}
