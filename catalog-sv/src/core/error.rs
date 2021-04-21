use thiserror::Error;

#[derive(Error, Debug)]
pub enum Error {
    #[error("error reading file: {0}")]
    ReadFileError(#[from] std::io::Error),
    #[error("error parsing date: {0}")]
    DateParseError(#[from] chrono::ParseError),
    #[error("error querying database: {0}")]
    DBQueryError(#[from] diesel::result::Error),
    #[error("error decoding anchor: {0}")]
    AnchorDecodeError(#[from] base64::DecodeError),
    #[error("error parsing anchor: {0}")]
    AnchorParseError(#[from] rmp_serde::decode::Error),
    #[error("error querying index: {0}")]
    IndexQueryError(#[from] elasticsearch::Error),
    #[error("error with part of query index")]
    IndexQueryPartialError,
    #[error("error serializing/deserializing json: {0}")]
    SerdeJsonError(#[from] serde_json::Error),
}