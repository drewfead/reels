#[macro_use]
extern crate diesel;
extern crate env_logger;
extern crate log;
extern crate rmp_serde;

use actix_web::{App, HttpServer, middleware};
use actix_web::web::{Data, scope};
use chrono::Duration;
use diesel::PgConnection;
use diesel::r2d2::ConnectionManager;
use elasticsearch::Elasticsearch;
use elasticsearch::http::transport::{SingleNodeConnectionPool, TransportBuilder};
use elasticsearch::http::Url;
use log::info;

use crate::core::action;

mod api;
mod core;
mod dmn;
mod db;
mod idx;

#[actix_web::main]
async fn main() -> std::io::Result<()> {
    dotenv::dotenv().ok();
    std::env::set_var("RUST_LOG",
      format!("{}actix_web=debug,hyper=info", std::env::var("RUST_LOG")
          .map_or_else(|_| "".to_string(), |ll| format!("{},", ll))
      ));
    env_logger::init();

    let pg_spec = std::env::var("DATABASE_URL").expect("DATABASE_URL");
    let pg_mgr = ConnectionManager::<PgConnection>::new(pg_spec);
    let pg_pool = Data::new(r2d2::Pool::builder()
        .build(pg_mgr)
        .expect("Failed to create pool."));

    let es_spec = std::env::var("INDEX_URL").expect("INDEX_URL");
    let es_url = Url::parse(&es_spec)
        .expect(format!("INDEX_URL was invalid {}", es_spec).as_str());
    let es_pool = SingleNodeConnectionPool::new(es_url);
    let es_tp = TransportBuilder::new(es_pool).disable_proxy().build()
        .expect("Couldn't construct ElasticSearch transport");
    let es = Data::new(Elasticsearch::new(es_tp));

    action::create_index(&es.clone().into_inner())
        .await
        .expect("Couldn't create index");

    let indexer = dmn::indexer::IndexDaemon::start(pg_pool.clone(), es.clone(), Duration::seconds(10));

    let deleter = dmn::deleter::DeleteDaemon::start(pg_pool.clone(), Duration::seconds(30));

    let bind = "127.0.0.1:8080";

    info!("Starting server at: {}", &bind);

    HttpServer::new(move || {
        App::new()
            .app_data(pg_pool.clone())
            .app_data(es.clone())
            .app_data(indexer.clone())
            .app_data(deleter.clone())
            .wrap(middleware::Logger::default())
            .service(api::health)
            .service(scope("/catalog")
                .service(api::post_movie)
                .service(api::get_movie)
                .service(api::put_movie)
                .service(api::delete_movie)
                .service(api::get_movies)
            )
    })
    .bind(&bind)?
    .max_connections(1000)
    .client_timeout(250)
    .run()
    .await
}