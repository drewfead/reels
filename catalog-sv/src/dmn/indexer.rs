use std::sync::Mutex;

use actix_web::error::BlockingError;
use actix_web::rt::time::{Instant, interval_at};
use actix_web::web;
use actix_web::web::Data;
use chrono::Duration;
use futures::StreamExt;
use log::error;

use crate::core::{action, Movie};
use crate::core::error::Error;
use crate::db::{DbConnection, DbConnectionPool};
use crate::idx::IndexClient;

pub struct IndexDaemon;

impl IndexDaemon {
    fn new() -> Self {
        IndexDaemon
    }

    async fn index(
        &self,
        pool: Data<DbConnectionPool>,
        client: Data<IndexClient>,
    ) -> Result<Vec<Movie>, BlockingError<Error>> {
        let conn1: DbConnection = pool.get()
            .expect("couldn't get db connection from pool");

        let to_index = web::block(move || action::find_movies_to_index(&conn1, 10))
            .await?;

        if to_index.is_empty() {
            return Ok(Vec::new())
        }

        let indexed = action::index_movies(&client, to_index).await
            .map_err(BlockingError::Error)?;

        let conn2: DbConnection = pool.get()
            .expect("couldn't get db connection from pool");

        web::block(move || action::mark_movies_indexed(&conn2, indexed))
            .await
    }

    fn spawn_indexer(
        me: Data<Mutex<Self>>,
        pool: Data<DbConnectionPool>,
        client: Data<IndexClient>,
        every: Duration,
    ) {
        actix_web::rt::spawn(async move {
            let mut task = interval_at(
                Instant::now(),
                every.to_std().expect("can't spawn on a negative interval"));
            while task.next().await.is_some() {
                me.lock().unwrap().index(pool.clone(), client.clone())
                    .await
                    .map_err(|err| error!("error indexing, {:?}", err))
                    .ok(); // continue on after errors
            }
        })
    }

    pub fn start(
        pool: Data<DbConnectionPool>,
        client: Data<IndexClient>,
        every: Duration,
    ) -> Data<Mutex<Self>> {
        let me = Data::new(Mutex::new(IndexDaemon::new()));
        Self::spawn_indexer(me.clone(), pool.clone(), client.clone(), every);
        me
    }
}