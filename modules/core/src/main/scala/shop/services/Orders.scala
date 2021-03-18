package shop.services

import shop.database.codecs._
import shop.domain.ID
import shop.domain.auth._
import shop.domain.cart._
import shop.domain.item._
import shop.domain.order._

import cats.effect._
import cats.syntax.all._
import skunk._
import skunk.circe.codec.all._
import skunk.codec.all._
import skunk.implicits._
import squants.market._

trait Orders[F[_]] {
  def get(userId: UserId, orderId: OrderId): F[Option[Order]]
  def findBy(userId: UserId): F[List[Order]]
  def create(
      userId: UserId,
      paymentId: PaymentId,
      items: List[CartItem],
      total: Money
  ): F[OrderId]
}

object Orders {
  def make[F[_]: Sync](
      sessionPool: Resource[F, Session[F]]
  ): Orders[F] =
    new Orders[F] {
      import OrderQueries._

      def get(userId: UserId, orderId: OrderId): F[Option[Order]] =
        sessionPool.use { session =>
          session.prepare(selectByUserIdAndOrderId).use { q =>
            q.option(userId ~ orderId)
          }
        }

      def findBy(userId: UserId): F[List[Order]] =
        sessionPool.use { session =>
          session.prepare(selectByUserId).use { q =>
            q.stream(userId, 1024).compile.toList
          }
        }

      def create(
          userId: UserId,
          paymentId: PaymentId,
          items: List[CartItem],
          total: Money
      ): F[OrderId] =
        sessionPool.use { session =>
          session.prepare(insertOrder).use { cmd =>
            ID.make[F, OrderId].flatMap { id =>
              val itMap = items.map(x => x.item.uuid -> x.quantity).toMap
              val order = Order(id, paymentId, itMap, total)
              cmd.execute(userId ~ order).as(id)
            }
          }
        }
    }

}

private object OrderQueries {

  val decoder: Decoder[Order] =
    (orderId ~ uuid ~ paymentId ~ jsonb[Map[ItemId, Quantity]] ~ numeric.map(USD.apply)).map {
      case o ~ _ ~ p ~ i ~ t =>
        Order(o, p, i, t)
    }

  val encoder: Encoder[UserId ~ Order] =
    (orderId ~ userId ~ paymentId ~ jsonb[Map[ItemId, Quantity]] ~ numeric.contramap[Money](_.amount)).contramap {
      case id ~ o =>
        o.id ~ id ~ o.paymentId ~ o.items ~ o.total
    }

  val selectByUserId: Query[UserId, Order] =
    sql"""
        SELECT * FROM orders
        WHERE user_id = ${userId}
       """.query(decoder)

  val selectByUserIdAndOrderId: Query[UserId ~ OrderId, Order] =
    sql"""
        SELECT * FROM orders
        WHERE user_id = ${userId}
        AND uuid = ${orderId}
       """.query(decoder)

  val insertOrder: Command[UserId ~ Order] =
    sql"""
        INSERT INTO orders
        VALUES ($encoder)
       """.command

}