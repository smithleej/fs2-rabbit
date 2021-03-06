/*
 * Copyright 2017 Fs2 Rabbit
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.github.gvolpe.fs2rabbit.program

import cats.effect.Concurrent
import com.github.gvolpe.fs2rabbit.algebra.{AMQPClient, AMQPInternals, Consumer}
import com.github.gvolpe.fs2rabbit.arguments.Arguments
import com.github.gvolpe.fs2rabbit.model._
import com.github.gvolpe.fs2rabbit.util.StreamEval
import com.rabbitmq.client.Channel
import fs2.{Pipe, Stream}
import fs2.concurrent.Queue

class ConsumerProgram[F[_]: Concurrent](AMQP: AMQPClient[Stream[F, ?], F])(implicit SE: StreamEval[F])
    extends Consumer[Stream[F, ?]] {

  private[fs2rabbit] def resilientConsumer: Pipe[F, Either[Throwable, AmqpEnvelope], AmqpEnvelope] =
    _.flatMap {
      case Left(err)  => Stream.raiseError[F](err)
      case Right(env) => SE.pure[AmqpEnvelope](env)
    }

  override def createConsumer(queueName: QueueName,
                              channel: Channel,
                              basicQos: BasicQos,
                              autoAck: Boolean = false,
                              noLocal: Boolean = false,
                              exclusive: Boolean = false,
                              consumerTag: String = "",
                              args: Arguments = Map.empty): StreamConsumer[F] =
    for {
      internalQ <- Stream.eval(Queue.bounded[F, Either[Throwable, AmqpEnvelope]](500))
      internals = AMQPInternals[F](Some(internalQ))
      _         <- AMQP.basicQos(channel, basicQos)
      _         <- AMQP.basicConsume(channel, queueName, autoAck, consumerTag, noLocal, exclusive, args)(internals)
      consumer  <- Stream.repeatEval(internalQ.dequeue1) through resilientConsumer
    } yield consumer

}
