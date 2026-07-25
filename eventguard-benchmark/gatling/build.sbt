// 最小 Gatling sbt 工程（M5.4）。可选：也可直接用官方 Gatling 发行版运行，无需 sbt。
// 详见同目录 README.md。

enablePlugins(GatlingPlugin)

scalaVersion := "2.13.12"

libraryDependencies += "io.gatling.highcharts" % "gatling-charts-highcharts" % "3.10.5" % Test
