module Models.Resources.AboutInfo exposing (AboutInfo, decoder)

import Models.Resources.UserInfo exposing (UserInfo, userInfoDecoder)
import Json.Decode as Decode exposing (field)

type alias ProjectInfo =
  { name : String
  , version : String
  }

type alias ScalaInfo =
  { version : String
  }

type alias SbtInfo =
  { version : String
  }

type alias AuthInfo =
  { enabled : Bool
  , userInfo : UserInfo
  }

type alias ServicesInfo =
  { clusterManagerInfo : ClusterManagerInfo
  , serviceDiscoveryInfo : ServiceDiscoveryInfo
  }

type alias ClusterManagerInfo =
  { connected : Bool
  }

type alias ServiceDiscoveryInfo =
  { connected : Bool
  }

type alias AboutInfo =
  { projectInfo : ProjectInfo
  , scalaInfo : ScalaInfo
  , sbtInfo : SbtInfo
  , authInfo : AuthInfo
  , services : ServicesInfo
  }

decoder =
  Decode.map5 AboutInfo
    (field "project" projectInfoDecoder)
    (field "scala" scalaInfoDecoder)
    (field "sbt" sbtInfoDecoder)
    (field "auth" authInfoDecoder)
    (field "services" servicesInfoDecoder)

projectInfoDecoder =
  Decode.map2 ProjectInfo
    (field "name" Decode.string)
    (field "version" Decode.string)

scalaInfoDecoder =
  Decode.map ScalaInfo
    (field "version" Decode.string)

sbtInfoDecoder =
  Decode.map SbtInfo
    (field "version" Decode.string)

authInfoDecoder =
  Decode.map2 AuthInfo
    (field "enabled" Decode.bool)
    (field "user" userInfoDecoder)

servicesInfoDecoder =
  Decode.map2 ServicesInfo
    (field "clusterManager" clusterManagerInfoDecoder)
    (field "serviceDiscovery" serviceDiscoveryInfoDecoder)

clusterManagerInfoDecoder =
  Decode.map ClusterManagerInfo
    (field "connected" Decode.bool)

serviceDiscoveryInfoDecoder =
  Decode.map ServiceDiscoveryInfo
    (field "connected" Decode.bool)
