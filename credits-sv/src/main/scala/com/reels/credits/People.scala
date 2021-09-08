package com.reels.credits

import java.nio.charset.StandardCharsets
import java.time.format.DateTimeFormatter
import java.time.{Instant, LocalDate}
import java.util.UUID

import cats.effect.Sync
import com.reels.credits.People.{CreateParameters, Deleted, PeopleError, Person, PersonId, UpdateParameters}
import io.chrisdavenport.log4cats.Logger

trait People[F[_]] {
  def create(params: CreateParameters): F[Either[PeopleError, Person]]
  def update(id: PersonId, params: UpdateParameters): F[Either[PeopleError, Person]]
  def delete(id: PersonId): F[Either[PeopleError, Deleted]]
  def findOne(id: PersonId): F[Either[PeopleError, Option[Person]]]
}

object People {
  sealed trait PeopleError

  case class QueryError() extends PeopleError
  case class CreateConflict() extends PeopleError

  trait Store[F[_]] {
    def create(person: Person): F[Either[PeopleError, Person]]
    def update(id: PersonId, changeset: PersonChangeset): F[Either[PeopleError, Person]]
    def findOne(id: PersonId): F[Either[PeopleError, Option[Person]]]
    def search(searchTerm: String): F[Either[PeopleError, Page[Person]]]
  }

  case class PersonId(value: String) extends AnyVal

  object PersonId {
    def apply(uuid: UUID): PersonId = {
      PersonId(value = uuid.toString)
    }

    def fromBytesOf(hash: String): PersonId = {
      PersonId(UUID.nameUUIDFromBytes(hash.getBytes(StandardCharsets.UTF_8)))
    }

    def fromBytesOfParts(parts: String*): PersonId = {
      fromBytesOf(parts.mkString(":"))
    }
  }

  sealed abstract class Deleted extends Serializable

  case object Deleted extends Deleted {
    def instance: Deleted = Deleted
  }

  case class CreateParameters(
    givenName: String,
    familyName: Option[String],
    dateOfBirth: LocalDate,
    dateOfDeath: Option[LocalDate],
    bio: Option[String],
    foreignUrl: Option[String],
  ) {
    def toPerson: Person = {
      val now = Instant.now()

      Person(
        id = PersonId.fromBytesOfParts(
          givenName, familyName.getOrElse(""), DateTimeFormatter.ISO_LOCAL_DATE.format(dateOfBirth),
        ),
        givenName = givenName,
        familyName = familyName,
        dateOfBirth = dateOfBirth,
        dateOfDeath = dateOfDeath,
        bio = bio,
        foreignUrl = foreignUrl,
        created = now,
        updated = now,
      )
    }
  }

  case object Delete {
    def toChangeset: PersonChangeset = {
      val now = Instant.now()

      PersonChangeset(
        deleted = Some(Some(now)),
      )
    }
  }

  case class UpdateParameters(
    givenName: Option[String],
    familyName: Option[Option[String]],
    dateOfBirth: Option[LocalDate],
    dateOfDeath: Option[Option[LocalDate]],
    bio: Option[Option[String]],
    foreignUrl: Option[Option[String]],
  ) {
    def toChangeset: PersonChangeset = {
      val now = Instant.now()

      PersonChangeset(
        givenName = givenName,
        familyName = familyName,
        dateOfBirth = dateOfBirth,
        dateOfDeath = dateOfDeath,
        bio = bio,
        foreignUrl = foreignUrl,
        updated = Some(now),
      )
    }
  }

  case class Person(
    id: PersonId,
    givenName: String,
    familyName: Option[String],
    dateOfBirth: LocalDate,
    dateOfDeath: Option[LocalDate],
    bio: Option[String],
    foreignUrl: Option[String],
    created: Instant,
    updated: Instant,
    deleted: Option[Instant] = None,
  )

  case class PersonChangeset(
    givenName: Option[String] = None,
    familyName: Option[Option[String]] = None,
    dateOfBirth: Option[LocalDate] = None,
    dateOfDeath: Option[Option[LocalDate]] = None,
    bio: Option[Option[String]] = None,
    foreignUrl: Option[Option[String]] = None,
    created: Option[Instant] = None,
    updated: Option[Instant] = None,
    deleted: Option[Option[Instant]] = None,
  )

  def impl[F[_]: Sync: Logger](store: Store[F]): People[F] = new People[F]{
    import cats.implicits._

    override def create(params: CreateParameters): F[Either[PeopleError, Person]] = for {
      _   <- Logger[F].info(s"action=create state=started $params")
      out <- store.create(params.toPerson)
        .onError {
          case err => Logger[F].error(err)(s"action=create state=error")
        }
      _   <- Logger[F].info(s"action=create state=finished $out")
    } yield out

    override def update(id: PersonId, params: UpdateParameters): F[Either[PeopleError, Person]] = for {
      _   <- Logger[F].info(s"action=update state=started id=${id.value} $params")
      out <- store.update(id, params.toChangeset)
        .onError {
          case error => Logger[F].error(error)(s"action=update state=error")
        }
      _   <- Logger[F].info(s"action=update state=finished id=${id.value} $out")
    } yield out

    override def delete(id: PersonId): F[Either[PeopleError, Deleted]] = for {
      _   <- Logger[F].info(s"action=delete state=started id=${id.value}")
      out <- store.update(id, Delete.toChangeset)
        .map { _ => Deleted.instance }
        .onError {
          case error => Logger[F].error(error)(s"action=delete state=error")
        }
      _   <- Logger[F].info(s"action=delete state=finished id=${id.value}")
    } yield out

    override def findOne(id: PersonId): F[Either[PeopleError, Option[Person]]] = for {
      _   <- Logger[F].info(s"action=findOne state=started id=${id.value}")
      out <- store.findOne(id)
        .onError {
          case error => Logger[F].error(error)(s"action=findOne state=error")
        }
      _   <- Logger[F].info(s"action=findOne state=finished id=${id.value} $out")
    } yield out
  }
}


