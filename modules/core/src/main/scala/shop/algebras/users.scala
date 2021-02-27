package shop.algebras

import shop.database.codecs._
import shop.domain.ID
import shop.domain.auth._
import shop.effects.GenUUID
import shop.http.auth.users._

import cats.effect._
import cats.syntax.all._
import skunk._
import skunk.implicits._

trait Users[F[_]] {
  def find(username: UserName, password: Password): F[Option[User]]
  def create(username: UserName, password: Password): F[UserId]
}

object Users {
  def make[F[_]: BracketThrow: GenUUID](
      sessionPool: Resource[F, Session[F]],
      crypto: Crypto
  ): Users[F] =
    new Users[F] {
      import UserQueries._

      def find(username: UserName, password: Password): F[Option[User]] =
        sessionPool.use { session =>
          session.prepare(selectUser).use { q =>
            q.option(username).map {
              case Some(u ~ p) if p.value == crypto.encrypt(password).value => u.some
              case _                                                        => none[User]
            }
          }
        }

      def create(username: UserName, password: Password): F[UserId] =
        sessionPool.use { session =>
          session.prepare(insertUser).use { cmd =>
            ID.make[F, UserId].flatMap { id =>
              cmd
                .execute(User(id, username) ~ crypto.encrypt(password))
                .as(id)
                .handleErrorWith {
                  case SqlState.UniqueViolation(_) =>
                    UserNameInUse(username).raiseError[F, UserId]
                }
            }
          }
        }
    }

}

private object UserQueries {

  val codec: Codec[User ~ EncryptedPassword] =
    (userId ~ userName ~ encPassword).imap {
      case i ~ n ~ p =>
        User(i, n) ~ p
    } {
      case u ~ p =>
        u.id ~ u.name ~ p
    }

  val selectUser: Query[UserName, User ~ EncryptedPassword] =
    sql"""
        SELECT * FROM users
        WHERE name = ${userName}
       """.query(codec)

  val insertUser: Command[User ~ EncryptedPassword] =
    sql"""
        INSERT INTO users
        VALUES ($codec)
        """.command

}
