use std::sync::Mutex;

use actix_web::error::BlockingError;
use actix_web::rt::time::{Instant, interval_at};
use actix_web::web;
use actix_web::web::Data;
use chrono::Duration;
use futures::StreamExt;
use log::error;

use crate::core::action;
use crate::core::error::Error;
use crate::db::{DbConnection, DbConnectionPool};

pub struct DeleteDaemon;

impl DeleteDaemon {
    fn new() -> Self {
        DeleteDaemon
    }

    async fn delete(
        &self,
        pool: Data<DbConnectionPool>,
    ) -> Result<usize, BlockingError<Error>> {
        let conn: DbConnection = pool.get()
            .expect("couldn't get db connection from pool");

        web::block(move || action::delete_soft_deleted(&conn))
            .await
    }

    fn spawn_deleter(
        me: Data<Mutex<Self>>,
        pool: Data<DbConnectionPool>,
        every: Duration,
    ) {
        actix_web::rt::spawn(async move {
            let mut task = interval_at(
                Instant::now(),
                every.to_std().expect("can't spawn on a negative interval"));
            while task.next().await.is_some() {
                me.lock().unwrap().delete(pool.clone())
                    .await
                    .map_err(|err| error!("error deleting, {:?}", err))
                    .ok(); // continue on after errors
            }
        })
    }

    pub fn start(
        pool: Data<DbConnectionPool>,
        every: Duration,
    ) -> Data<Mutex<Self>> {
        let me = Data::new(Mutex::new(DeleteDaemon::new()));
        Self::spawn_deleter(me.clone(), pool.clone(), every);
        me
    }
}