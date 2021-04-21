use log::{debug, info};
use uuid::Uuid;

use crate::core::{Movie, Page, CreateMovieParams, UpdateMovieParams, IndexMovie};
use crate::core::error::Error;
use crate::db;
use crate::db::DbConnection;
use crate::idx;
use crate::idx::IndexClient;

pub fn create_movie(conn: &DbConnection, movie: CreateMovieParams) -> Result<Option<Movie>, Error> {
    info!("creating movie {:?}", movie);
    db::create_movie(conn, movie.create())
}

pub fn update_movie(conn: &DbConnection, id: Uuid, movie: UpdateMovieParams) -> Result<Movie, Error> {
    info!("updating movie id={} {:?}", id, movie);
    db::update_movie(conn, id, movie.update())
}

pub fn delete_movie(conn: &DbConnection, id: Uuid) -> Result<bool, Error> {
    info!("deleting movie id={}", id);
    db::delete_movie(conn, id)
}

pub fn find_one_movie(conn: &DbConnection, id: Uuid) -> Result<Option<Movie>, Error> {
    info!("finding movie id={}", id);
    db::find_one_movie(conn, id)
}

pub fn find_movies(conn: &DbConnection, count: i64, anchor: &Option<String>) -> Result<Page<Movie>, Error> {
    info!("finding movies count={:?} anchor={:?}", count, anchor);
    db::find_movies(conn, count, anchor)
}

pub async fn search_movies(
    client: &IndexClient,
    search_term: &String,
    count: i64,
    anchor: &Option<String>
) -> Result<Page<Movie>, Error> {
    info!("searching movies search_term={} count={:?} anchor={:?}", search_term, count, anchor);
    idx::search_movies(client, search_term, count, anchor).await
}

pub async fn create_index(client: &IndexClient) -> Result<bool, Error> {
    info!("creating catalog index");
    idx::create_index(client).await
}

pub fn find_movies_to_index(conn: &DbConnection, count: i64) -> Result<Vec<Movie>, Error> {
    debug!("finding movies to index count={:?}", count);
    db::find_stale_indexed(conn, count)
}

pub async fn index_movies(client: &IndexClient, movies: Vec<Movie>) -> Result<Vec<Movie>, Error> {
    info!("adding movies to catalog index {:?}", movies);
    idx::index_movies(client, movies).await
}

pub fn mark_movies_indexed(conn: &DbConnection, movies: Vec<Movie>) -> Result<Vec<Movie>, Error> {
    info!("marking movies indexed {:?}", movies);
    db::update_movies(
        conn,
        movies.iter().map(|m|m.id).collect(),
        IndexMovie.update()
    )
}