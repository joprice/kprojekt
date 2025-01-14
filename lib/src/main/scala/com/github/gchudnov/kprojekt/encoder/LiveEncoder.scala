package com.github.gchudnov.kprojekt.encoder

import com.github.gchudnov.kprojekt.formatter.Folder
import com.github.gchudnov.kprojekt.ids.NodeIdOrdering._
import com.github.gchudnov.kprojekt.ids._
import org.apache.kafka.streams.TopologyDescription
import org.apache.kafka.streams.TopologyDescription._
import zio.{ Has, UIO, ZIO, ZLayer }

import scala.jdk.CollectionConverters._

/**
 * Encodes TopologyDescription to a string.
 */
final class LiveEncoder(folder: Folder) extends Encoder {
  import LiveEncoder._

  override def encode(name: String, desc: TopologyDescription): UIO[String] =
    UIO {
      val subtopologies = desc.subtopologies().asScala.toSeq.sortBy(_.id())
      val globalStores  = desc.globalStores().asScala.toSeq.sortBy(_.id())

      val maybeTopicRelatedNodes = subtopologies.flatMap(_.nodes().asScala) ++ globalStores.map(_.source())
      val topics                 = collectTopics(maybeTopicRelatedNodes)
      val topicEdges             = collectTopicEdges(maybeTopicRelatedNodes)

      val allNodes = collectTopologyNodes(desc)

      folder
        .topologyStart(name)
        .repository(allNodes)
        .topics { ra =>
          topics.foldLeft(ra) { (acc, t) =>
            acc.topic(t)
          }
        }
        .edges { ra =>
          topicEdges.foldLeft(ra) { (acc, e) =>
            acc.edge(e._1, e._2)
          }
        }
        .subtopologies { ra =>
          subtopologies.foldLeft(ra) { (acc, st) =>
            val nodes                        = collectNodes(st)
            val (sources, processors, sinks) = collectNodeByType(nodes)
            collectSubtopology(acc)(st.id().toString, sources, processors, sinks)
          }
        }
        .subtopologies { ra =>
          globalStores.foldLeft(ra) { (acc, gs) =>
            val sources    = Seq(gs.source())
            val processors = Seq(gs.processor())
            val sinks      = Seq.empty[Sink]
            collectSubtopology(acc)(gs.id().toString, sources, processors, sinks)
          }
        }
        .topologyEnd()
        .toString
    }

}

object LiveEncoder {
  private val KeySource    = "s"
  private val KeyProcessor = "p"
  private val KeySink      = "k"

  def layer: ZLayer[Has[Folder], Nothing, Has[Encoder]] =
    (for {
      folder <- ZIO.service[Folder]
      service = new LiveEncoder(folder)
    } yield service).toLayer

  private def collectSubtopology(ra: Folder)(stName: String, sources: Seq[Source], processors: Seq[Processor], sinks: Seq[Sink]): Folder = {
    val nodeEdges  = collectNodeEdges(sources ++ processors ++ sinks.asInstanceOf[Seq[Node]])
    val stores     = collectStores(processors)
    val storeEdges = collectStoreEdges(processors)

    ra.storeEdges(storeEdges)
      .subtopologyStart(stName)
      .edges { ra =>
        nodeEdges.foldLeft(ra) { (acc, e) =>
          acc.edge(e._1, e._2)
        }
      }
      .sources { ra =>
        sources.foldLeft(ra) { (acc, s) =>
          val sid = toNodeId(s)
          val ts  = s.topicSet().asScala.toSeq.map(TopicId).sorted
          acc.source(sid, ts)
        }
      }
      .processors { ra =>
        processors.foldLeft(ra) { (acc, p) =>
          val pn = toNodeId(p)
          val ss = p.stores().asScala.toSeq.map(StoreId).sorted
          acc.processor(pn, ss)
        }
      }
      .sinks { ra =>
        sinks.foldLeft(ra) { (acc, k) =>
          acc.sink(toNodeId(k), TopicId(k.topic()))
        }
      }
      .stores { ra =>
        stores.foldLeft(ra) { (acc, r) =>
          acc.store(r)
        }
      }
      .edges { ra =>
        storeEdges.foldLeft(ra) { (acc, e) =>
          acc
            .edge(e._1, e._2)
        }
      }
      .subtopologyEnd()
  }

  private def collectTopics(nodes: Seq[Node]): Seq[NodeId] =
    nodes.collect {
      case s: Source => s.topicSet().asScala.toSet
      case k: Sink   => Set(k.topic())
    }.flatten
      .map(TopicId)
      .distinct
      .sorted

  private def collectTopicEdges(nodes: Seq[Node]): Seq[(NodeId, NodeId)] =
    nodes.collect {
      case s: Source => s.topicSet().asScala.toSet.map((t: String) => (TopicId(t), toNodeId(s)))
      case k: Sink   => Set(k.topic()).map(t => (toNodeId(k), TopicId(t)))
    }.flatten.distinct.sorted

  private def collectNodes(subtopology: Subtopology): Seq[Node] =
    subtopology.nodes().asScala.toSeq.distinctBy(_.name()).sortBy(_.name())

  private def collectNodeEdges(nodes: Seq[Node]): Seq[(NodeId, NodeId)] =
    nodes.flatMap(from => from.successors().asScala.map(to => (toNodeId(from), toNodeId(to)))).distinct.sorted

  private def collectNodeByType(nodes: Seq[Node]): (Seq[Source], Seq[Processor], Seq[Sink]) = {
    val m = nodes.groupBy {
      case _: Source    => KeySource
      case _: Processor => KeyProcessor
      case _: Sink      => KeySink
      case n            => sys.error(s"invalid node type: $n")
    }

    val sources    = m.getOrElse(KeySource, Set.empty[Node]).map(_.asInstanceOf[Source])
    val processors = m.getOrElse(KeyProcessor, Set.empty[Node]).map(_.asInstanceOf[Processor])
    val sinks      = m.getOrElse(KeySink, Set.empty[Node]).map(_.asInstanceOf[Sink])

    (sources.toSeq.distinctBy(_.name()).sortBy(_.name()), processors.toSeq.distinctBy(_.name()).sortBy(_.name()), sinks.toSeq.distinctBy(_.name()).sortBy(_.name()))
  }

  private def collectStores(processors: Seq[Processor]): Seq[NodeId] =
    processors
      .foldLeft(Set.empty[NodeId]) { (acc, p) =>
        acc ++ p.stores().asScala.map(StoreId)
      }
      .toSeq
      .distinct
      .sorted

  private def collectStoreEdges(processors: Seq[Processor]): Seq[(NodeId, NodeId)] =
    processors
      .foldLeft(Set.empty[(NodeId, NodeId)]) { (acc, p) =>
        acc ++ p.stores().asScala.map(storeName => (toNodeId(p), StoreId(storeName)))
      }
      .toSeq
      .distinct
      .sorted

  private[encoder] def collectTopologyNodes(desc: TopologyDescription): Seq[NodeId] = {
    val subtopologies = desc.subtopologies().asScala.toSeq
    val globalStores  = desc.globalStores().asScala.toSeq

    val globalStoreNames = globalStores.foldLeft(Seq.empty[NodeId]) { (acc, gs) =>
      acc ++ collectNodes(Seq(gs.source()), Seq(gs.processor()), Seq.empty[Sink])
    }

    subtopologies
      .foldLeft(globalStoreNames) { (acc, st) =>
        val ns           = st.nodes().asScala.toSeq
        val (ss, ps, ks) = collectNodeByType(ns)
        acc ++ collectNodes(ss, ps, ks)
      }
      .distinct
      .sorted
  }

  private def collectNodes(sources: Seq[Source], processors: Seq[Processor], sinks: Seq[Sink]): Seq[NodeId] = {
    val sourceNodes = sources.flatMap(s => toNodeId(s) +: s.topicSet().asScala.toSeq.map(TopicId))
    val procNodes   = processors.flatMap(p => toNodeId(p) +: p.stores().asScala.toSeq.map(StoreId))
    val sinkNodes   = sinks.flatMap(k => Seq(toNodeId(k), TopicId(k.topic())))
    (sourceNodes ++ procNodes ++ sinkNodes)
  }

  private def toNodeId(node: Node): NodeId =
    node match {
      case s: Source =>
        SourceId(s.name())
      case p: Processor =>
        ProcessorId(p.name())
      case k: Sink =>
        SinkId(k.name())
      case _ => sys.error(s"invalid node type: $node")
    }
}
