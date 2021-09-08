package com.reels.credits.db

import cats.effect.{Sync, Timer}
import cats.syntax.all._
import com.basho.riak.client.api.RiakClient
import com.basho.riak.client.api.cap.UnresolvedConflictException
import com.basho.riak.client.api.commands.kv.{FetchValue, StoreValue, UpdateValue}
import com.basho.riak.client.core.query.{Location, Namespace}
import com.reels.credits.{Page, People}
import com.reels.credits.People.{CreateConflict, PeopleError, Person, PersonChangeset, PersonId, QueryError}
import io.chrisdavenport.log4cats.Logger
import com.reels.credits.db.RiakFutureOps._

import scala.concurrent.duration.FiniteDuration

object PeopleStore {
  val namespace = new Namespace("people")

  private def location(id: PersonId): Location = {
    new Location(namespace, id.value)
  }

  implicit class ChangesetOps(changeset: PersonChangeset) {
    def asUpdate: UpdateValue.Update[Person] = (original: Person) => {
      Person(
        id = original.id,
        givenName = changeset.givenName.getOrElse(original.givenName),
        familyName = changeset.familyName.getOrElse(original.familyName),
        dateOfBirth = changeset.dateOfBirth.getOrElse(original.dateOfBirth),
        dateOfDeath = changeset.dateOfDeath.getOrElse(original.dateOfDeath),
        bio = changeset.bio.getOrElse(original.bio),
        foreignUrl = changeset.foreignUrl.getOrElse(original.foreignUrl),
        created = changeset.created.getOrElse(original.created),
        updated = changeset.updated.getOrElse(original.updated),
        deleted = changeset.deleted.getOrElse(original.deleted),
      )
    }
  }

  def riakKV[F[_]: Timer: Logger](
    client: RiakClient,
    pollEvery: FiniteDuration
  )(implicit
    sync: Sync[F]
  ): People.Store[F] = new People.Store[F] {

    private def parseResponse[A, C](
       response: (A, C),
       extractor: A => Person,
       shortCircuitCondition: A => Boolean = _ => false,
    ): (Either[PeopleError, Option[Person]], C) =
      response match {
        case (results, queryContext) =>
          if(shortCircuitCondition(results)) {
            Option.empty[Person].asRight[PeopleError] -> queryContext
          } else {
            try {
              extractor(results).some.asRight[PeopleError] -> queryContext
            } catch {
              case _: Throwable => QueryError().asLeft[Option[Person]] -> queryContext
            }
          }
      }

    override def findOne(id: PersonId): F[Either[PeopleError, Option[Person]]] = {
      client.executeAsync(new FetchValue.Builder(location(id)).build()).lift[F](pollEvery)
        .map { response => parseResponse[FetchValue.Response, Location](
          response,
          _.getValue(classOf[Person]),
          _.hasValues != true
        )}
        .map {
          case (result, _) => result
        }
    }

    override def create(person: Person): F[Either[PeopleError, Person]] = {
      client.executeAsync(
        new StoreValue.Builder(person)
          .withLocation(location(person.id))
          .withOption(StoreValue.Option.IF_NONE_MATCH, true)
          .withOption(StoreValue.Option.RETURN_BODY, true)
          .build()
      ).lift[F](pollEvery)
        .map { response => parseResponse[StoreValue.Response, Location](
          response,
          _.getValue(classOf[Person]),
          _.hasValues
        )}
        .map {
          case (Left(err), _) => Left(err)
          case (Right(None), _) => Left(QueryError())
          case (Right(Some(r)), _) => Right(r)
        }
    }


    override def update(id: PersonId, changeset: PersonChangeset): F[Either[PeopleError, Person]] =  {
      client.executeAsync(
        new UpdateValue.Builder(location(id))
          .withFetchOption(FetchValue.Option.DELETED_VCLOCK, true)
          .withStoreOption(StoreValue.Option.RETURN_BODY, true)
          .withUpdate(changeset.asUpdate)
          .build()
      ).lift[F](pollEvery)
        .map { response => parseResponse[UpdateValue.Response, Location](
          response,
          _.getValue(classOf[Person]),
        )}
        .map {
          case (Left(err), _) => Left(err)
          case (Right(None), _) => Left(QueryError())
          case (Right(Some(r)), _) => Right(r)
        }
    }

    override def search(searchTerm: String): F[Either[PeopleError, Page[Person]]] = {
      sync.delay(Left(QueryError()))
    }
  }
}
