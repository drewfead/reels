use diesel::pg::Pg;
use diesel::prelude::*;
use diesel::query_builder::*;
use diesel::query_dsl::methods::LoadQuery;
use diesel::sql_types::BigInt;

pub trait CountRemaining: Sized {
    fn count_remaining(self, page_size: i64) -> RemainingCounted<Self>;
}

impl<T> CountRemaining for T {
    fn count_remaining(self, page_size: i64) -> RemainingCounted<Self> {
        RemainingCounted {
            query: self,
            page_size
        }
    }
}

#[derive(Debug, Clone, Copy, QueryId)]
pub struct RemainingCounted<T> {
    query: T,
    page_size: i64,
}

impl<T> RemainingCounted<T> {
    pub fn load_and_count_remaining<U>(self, conn: &PgConnection) -> QueryResult<(Vec<U>, i64)>
        where
            Self: LoadQuery<PgConnection, (U, i64)>,
    {
        let results = self.load::<(U, i64)>(conn)?;
        let total = results.get(0).map(|x| x.1).unwrap_or(0);
        let remaining = total - results.len() as i64;
        let records = results.into_iter().map(|x| x.0).collect();

        Ok((records, remaining))
    }
}

impl<T: Query> Query for RemainingCounted<T> {
    type SqlType = (T::SqlType, BigInt);
}

impl<T> RunQueryDsl<PgConnection> for RemainingCounted<T> {}

impl<T> QueryFragment<Pg> for RemainingCounted<T>
    where
        T: QueryFragment<Pg>,
{
    fn walk_ast(&self, mut out: AstPass<Pg>) -> QueryResult<()> {
        out.push_sql("SELECT *, COUNT(*) OVER () FROM (");
        self.query.walk_ast(out.reborrow())?;
        out.push_sql(") t LIMIT ");
        out.push_bind_param::<BigInt, _>(&self.page_size)?;
        Ok(())
    }
}
