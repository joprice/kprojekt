package com.github.gchudnov.kprojekt.formatter.dot

final case class DotConfig(
                            indent: Int,
                            fontName: String,
                            fontSize: Int,
                            isEmbedStore: Boolean,
                            hasLegend: Boolean
                          )

object DotConfig {
  val cylinderFileName: String = "cylinder.png"
}
