use std::io::Write;

use diesel::{deserialize, serialize};
use diesel::deserialize::FromSql;
use diesel::pg::Pg;
use diesel::serialize::{Output, ToSql, WriteTuple};
use diesel::sql_types::{Record, Text, Uuid};

use crate::core::{Country, Genre, Language};

#[derive(SqlType)]
#[postgres(type_name = "language")]
pub struct PgLanguage;

#[derive(SqlType)]
#[postgres(type_name = "country")]
pub struct PgCountry;

#[derive(SqlType)]
#[postgres(type_name = "genre")]
pub struct PgGenre;

impl ToSql<PgGenre, Pg> for Genre {
    fn to_sql<W: Write>(&self, out: &mut Output<W, Pg>) -> serialize::Result {
        WriteTuple::<(Uuid, Text)>::write_tuple(
            &(self.id.clone(), self.name.clone()),
            out,
        )
    }
}

impl FromSql<PgGenre, Pg> for Genre {
    fn from_sql(bytes: Option<&[u8]>) -> deserialize::Result<Self> {
        let (id, name) =
            FromSql::<Record<(Uuid, Text)>, Pg>::from_sql(bytes)?;

        Ok(Genre { id, name })
    }
}

impl ToSql<PgLanguage, Pg> for Language {
    fn to_sql<W: Write>(&self, out: &mut Output<W, Pg>) -> serialize::Result {
        WriteTuple::<(Text, Text)>::write_tuple(
            &(self.code.clone(), self.name.clone()),
            out,
        )
    }
}

impl FromSql<PgLanguage, Pg> for Language {
    fn from_sql(bytes: Option<&[u8]>) -> deserialize::Result<Self> {
        let (code, name) = FromSql::<Record<(Text, Text)>, Pg>::from_sql(bytes)?;

        Ok(Language { code, name })
    }
}

impl ToSql<PgCountry, Pg> for Country {
    fn to_sql<W: Write>(&self, out: &mut Output<W, Pg>) -> serialize::Result {
        WriteTuple::<(Text, Text)>::write_tuple(
            &(self.code.clone(), self.name.clone()),
            out,
        )
    }
}

impl FromSql<PgCountry, Pg> for Country {
    fn from_sql(bytes: Option<&[u8]>) -> deserialize::Result<Self> {
        let (code, name) = FromSql::<Record<(Text, Text)>, Pg>::from_sql(bytes)?;

        Ok(Country { code, name })
    }
}