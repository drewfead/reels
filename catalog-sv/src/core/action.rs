use log::{debug, info};
use uuid::Uuid;

use crate::core::{CreateMovieParams, DeleteMovie, IndexMovie, Movie, Page, UpdateMovieParams, HasId};
use crate::core::error::Error;
use crate::db;
use crate::db::DbConnection;
use crate::idx;
use crate::idx::IndexClient;
use either::Either;

pub fn create_movie(conn: &DbConnection, movie: CreateMovieParams) -> Result<Either<HasId, Movie>, Error> {
    info!("creating movie {:?}", movie);
    db::create_movie(conn, movie.create())
}

pub fn update_movie(conn: &DbConnection, id: Uuid, movie: UpdateMovieParams) -> Result<Option<Movie>, Error> {
    info!("updating movie id={} {:?}", id, movie);
    db::update_movie(conn, id, movie.update())
}

pub fn delete_movie(conn: &DbConnection, id: Uuid) -> Result<bool, Error> {
    debug!("deleting movie id={}", id);
    let soft_deleted = db::update_movie(conn, id, DeleteMovie.update())
        .map(|opt| opt.is_some())?;

    info!("soft deleted movie id={}", id);
    Ok(soft_deleted)
}

pub fn delete_soft_deleted(conn: &DbConnection) -> Result<usize, Error> {
    debug!("deleting movies that have been soft deleted");
    let deleted = db::delete_soft_deleted(conn)?;
    if deleted > 0 {
        info!("deleted {} movies", deleted);
    }

    Ok(deleted)
}

pub fn find_one_movie(conn: &DbConnection, id: Uuid) -> Result<Option<Movie>, Error> {
    info!("finding movie id={}", id);
    db::find_one_movie(conn, id)
}

pub fn find_movies(conn: &DbConnection, count: i64, anchor: &Option<String>) -> Result<Page<Movie>, Error> {
    info!("finding movies count={:?} anchor={:?}", count, anchor);
    db::find_movies(conn, count, anchor)
}

pub fn find_movies_with_ids(conn: &DbConnection, ids: Vec<Uuid>) -> Result<Page<Movie>, Error> {
    if ids.is_empty() {
        return Ok(Page {
            page_number: 1,
            next_anchor: None,
            items: Vec::new(),
        })
    }

    info!("finding movies with ids in {:?}", ids);
    db::find_movies_with_ids(conn, ids)
}

pub async fn search_movies(
    client: &IndexClient,
    search_term: &String,
    count: i64,
    anchor: &Option<String>
) -> Result<Page<Either<HasId, Movie>>, Error> {
    info!("searching movies search_term={} count={:?} anchor={:?}", search_term, count, anchor);
    idx::search_movies(client, search_term, count, anchor).await
}

pub async fn create_index(client: &IndexClient) -> Result<bool, Error> {
    info!("creating catalog index");
    idx::create_index(client).await
}

pub fn find_movies_to_index(conn: &DbConnection, count: i64) -> Result<Vec<Movie>, Error> {
    debug!("finding movies to index count={:?}", count);
    let stale = db::find_stale_indexed(conn, count)?;
    if !stale.is_empty() {
        info!("found stale movies to index count={:?}", stale.len())
    }
    Ok(stale)
}

pub async fn index_movies(client: &IndexClient, movies: Vec<Movie>) -> Result<Vec<Movie>, Error> {
    info!("adding movies to catalog index {:?}", movies);
    idx::index_movies(client, movies).await
}

pub fn mark_movies_indexed(conn: &DbConnection, movies: Vec<Movie>) -> Result<Vec<Movie>, Error> {
    debug!("marking movies indexed {:?}", movies);
    db::update_movies(
        conn,
        movies.iter().map(|m|m.id).collect(),
        IndexMovie.update()
    )
}