use base64::URL_SAFE_NO_PAD;
use elasticsearch::{BulkParts, Elasticsearch, SearchParts};
use elasticsearch::http::request::JsonBody;
use elasticsearch::indices::{IndicesCreateParts, IndicesExistsParts};
use log::debug;
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};

use crate::core::{Movie, Page, HasId};
use crate::core::error::Error;
use crate::core::error::Error::{AnchorDecodeError, AnchorParseError, IndexQueryError, IndexQueryPartialError, SerdeJsonError};
use either::Either;
use either::Either::{Right, Left};

mod schema;

pub type IndexClient = Elasticsearch;

pub async fn create_index(client: &IndexClient) -> Result<bool, Error> {
    let exists = client.indices()
        .exists(IndicesExistsParts::Index(&[schema::INDEX_NAME]))
        .send()
        .await?
        .error_for_status_code();

    match exists {
        Ok(_) => Ok(true),
        Err(_) => client.indices()
            .create(IndicesCreateParts::Index(schema::INDEX_NAME))
            .body(schema::schema())
            .send()
            .await?
            .error_for_status_code()
            .map(|_| true)
            .map_err(IndexQueryError)
    }
}

pub async fn index_movies(client: &IndexClient, movies: Vec<Movie>) -> Result<Vec<Movie>, Error> {
    if movies.is_empty() {
        return Ok(Vec::new())
    }

    let mut body: Vec<JsonBody<_>> = Vec::with_capacity(movies.len() * 2);

    movies.iter().try_for_each(|m: &Movie| {
        match m.deleted {
            None => serde_json::to_value(m).map(|json| {
                body.push(json!({"index": {"_id": m.id.clone()}}).into());
                body.push(JsonBody::new(json))
            }),
            Some(_) => {
                body.push(json!({"delete": {"_id": m.id.clone()}}).into());
                Ok(())
            }
        }

    }).map_err(SerdeJsonError)?;

    let response = client
        .bulk(BulkParts::Index(schema::INDEX_NAME))
        .body(body)
        .send()
        .await?;

    let response_body = response.json::<Value>().await?;
    let successful = response_body["errors"].as_bool().unwrap() == false;

    if successful {
        Ok(movies)
    } else {
        Err(IndexQueryPartialError)
    }
}

#[derive(Clone, Debug, Deserialize, Serialize)]
struct MovieAnchor {
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

pub async fn search_movies(
    client: &IndexClient,
    search_term: &String,
    count: i64,
    anchor: &Option<String>
) -> Result<Page<Either<HasId, Movie>>, Error> {
    let this_page = match anchor {
        None => 1,
        Some(anch) => deserialize_anchor(anch.to_string())?.page_number
    };

    let from = (this_page - 1) * count;
    
    let query = json!({
            "query": {
                "simple_query_string": {
                    "query": format!("\"{}\"", search_term),
                    "fields": [ "title" ],
                }
            }
        });
    
    debug!("{}", query);

    let response: Value = client
        .search(SearchParts::Index(&[schema::INDEX_NAME]))
        .from(from)
        .size(count)
        .body(query)
        .send()
        .await?
        .error_for_status_code()
        .map_err(IndexQueryError)?
        .json()
        .await
        .map_err(IndexQueryError)?;
    
    debug!("{}", response);

    let total_count: i64 = response["hits"]["total"]["value"]
        .as_i64()
        .unwrap_or(0);

    let remaining_count = total_count - this_page * count;

    let items: Vec<Either<HasId, Movie>> = response["hits"]["hits"]
        .as_array()
        .unwrap()
        .iter()
        .map(|hit|
            serde_json::from_value::<Movie>(hit["_source"].clone())
                .map(|m| Right(m))
                .or_else(|_|
                    serde_json::from_value::<HasId>(hit["_source"].clone())
                        .map(|id| Left(id))
                )
                .unwrap()
        )
        .collect();

    let next_anchor = if remaining_count > 0 {
        Some(MovieAnchor {
            page_number: this_page + 1
        })
    } else {
        None
    };
    
    Ok(Page {
        page_number: this_page,
        next_anchor: next_anchor.map(serialize_anchor),
        items,
    })
}