#include <unordered_map>

#include <mbgl/map/map.hpp>
#include <mbgl/map/map_options.hpp>
#include <mbgl/renderer/query.hpp>
#include <mbgl/renderer/renderer.hpp>
#include <mbgl/storage/file_source.hpp>
#include <mbgl/storage/file_source_manager.hpp>
#include <mbgl/storage/resource_options.hpp>
#include <mbgl/style/conversion/filter.hpp>
#include <mbgl/style/conversion/geojson.hpp>
#include <mbgl/style/conversion/json.hpp>
#include <mbgl/style/conversion_impl.hpp>
#include <mbgl/style/image.hpp>
#include <mbgl/style/layers/background_layer.hpp>
#include <mbgl/style/layers/circle_layer.hpp>
#include <mbgl/style/layers/fill_extrusion_layer.hpp>
#include <mbgl/style/layers/fill_layer.hpp>
#include <mbgl/style/layers/heatmap_layer.hpp>
#include <mbgl/style/layers/hillshade_layer.hpp>
#include <mbgl/style/layers/line_layer.hpp>
#include <mbgl/style/layers/raster_layer.hpp>
#include <mbgl/style/layers/symbol_layer.hpp>
#include <mbgl/style/sources/geojson_source.hpp>
#include <mbgl/style/sources/image_source.hpp>
#include <mbgl/style/sources/raster_dem_source.hpp>
#include <mbgl/style/sources/raster_source.hpp>
#include <mbgl/style/sources/vector_source.hpp>
#include <mbgl/style/style.hpp>
#include <mbgl/util/client_options.hpp>
#include <mbgl/util/feature.hpp>
#include <mbgl/util/geojson.hpp>
#include <mbgl/util/immutable.hpp>
#include <mbgl/util/premultiply.hpp>
#include <mbgl/util/rapidjson.hpp>
#include <mbgl/util/tileset.hpp>

#include <jni.h>
#include <mapbox/geojson.hpp>
#include <mapbox/geojson_impl.hpp>
#include <smjni/java_exception.h>
#include <smjni/java_string.h>

#include <MapLibreMap_class.h>

#include "canvas_renderer.hpp"
#include "conversions.hpp"
#include "java_classes.hpp"
#include "jni_map_observer.hpp"

#pragma mark - Helpers

struct MapWrapper {
  std::unique_ptr<maplibre_jni::JniMapObserver> observer;
  std::unique_ptr<mbgl::Map> map;
  maplibre_jni::CanvasRenderer* renderer;
  std::unordered_map<std::string, std::unique_ptr<mbgl::style::Layer>>
    preservedLayers;

  MapWrapper(
    mbgl::Map* map, maplibre_jni::JniMapObserver* observer,
    maplibre_jni::CanvasRenderer* renderer
  )
      : observer(observer), map(map), renderer(renderer) {}
};

template <typename Func>
auto withMapWrapper(JNIEnv* env, jMapLibreMap map, Func&& func)
  -> decltype(func(std::declval<MapWrapper*>())) {
  using ReturnType = decltype(func(std::declval<MapWrapper*>()));
  try {
    auto ptr =
      java_classes::get<MapLibreMap_class>().getNativePointer(env, map);
    // NOLINTNEXTLINE(cppcoreguidelines-pro-type-reinterpret-cast)
    auto* wrapper = reinterpret_cast<MapWrapper*>(ptr);
    return std::forward<Func>(func)(wrapper);
  } catch (const std::exception& e) {
    smjni::java_exception::translate(env, e);
    if constexpr (!std::is_void_v<ReturnType>) return ReturnType{};
  }
}

static mbgl::style::Layer* getLayerOrThrow(
  MapWrapper* wrapper, const std::string& id
) {
  auto* layer = wrapper->map->getStyle().getLayer(id);
  if (!layer) throw std::runtime_error("Layer not found: " + id);
  return layer;
}

static double getJsonNumber(const mbgl::JSValue& v, double def = 0.0) {
  if (v.IsDouble()) return v.GetDouble();
  if (v.IsInt()) return static_cast<double>(v.GetInt());
  if (v.IsUint()) return static_cast<double>(v.GetUint());
  if (v.IsInt64()) return static_cast<double>(v.GetInt64());
  if (v.IsUint64()) return static_cast<double>(v.GetUint64());
  return def;
}

static mbgl::Tileset parseTileset(const mbgl::JSValue& config) {
  mbgl::Tileset tileset;
  if (!config.IsObject()) return tileset;

  if (config.HasMember("tiles") && config["tiles"].IsArray()) {
    for (const auto& t : config["tiles"].GetArray()) {
      if (t.IsString())
        tileset.tiles.emplace_back(t.GetString(), t.GetStringLength());
    }
  }

  if (config.HasMember("minzoom"))
    tileset.zoomRange.min =
      static_cast<uint8_t>(getJsonNumber(config["minzoom"]));
  if (config.HasMember("maxzoom"))
    tileset.zoomRange.max =
      static_cast<uint8_t>(getJsonNumber(config["maxzoom"]));

  if (config.HasMember("scheme") && config["scheme"].IsString()) {
    if (std::string(config["scheme"].GetString()) == "tms")
      tileset.scheme = mbgl::Tileset::Scheme::TMS;
  }

  if (config.HasMember("attribution") && config["attribution"].IsString())
    tileset.attribution = std::string(config["attribution"].GetString());

  if (config.HasMember("encoding") && config["encoding"].IsString()) {
    if (std::string(config["encoding"].GetString()) == "terrarium")
      tileset.encoding = mbgl::Tileset::DEMEncoding::Terrarium;
  }

  if (config.HasMember("bounds") && config["bounds"].IsArray()) {
    const auto& arr = config["bounds"].GetArray();
    if (arr.Size() == 4) {
      double w = getJsonNumber(arr[0]);
      double s = getJsonNumber(arr[1]);
      double e = getJsonNumber(arr[2]);
      double n = getJsonNumber(arr[3]);
      tileset.bounds =
        mbgl::LatLngBounds::hull(mbgl::LatLng(s, w), mbgl::LatLng(n, e));
    }
  }

  return tileset;
}

static uint16_t parseTileSize(const mbgl::JSValue& config) {
  if (config.IsObject() && config.HasMember("tileSize"))
    return static_cast<uint16_t>(getJsonNumber(config["tileSize"], 512));
  return 512;
}

static std::unique_ptr<mbgl::style::Source> createTileSource(
  const std::string& type, const std::string& id, const std::string& configJson
) {
  mbgl::JSDocument document;
  document.Parse<0>(configJson.c_str());
  if (document.HasParseError()) {
    throw std::runtime_error("Failed to parse tile source config JSON");
  }

  // If config is a simple string, it's a URL
  if (document.IsString()) {
    std::string url(document.GetString(), document.GetStringLength());
    if (type == "vector")
      return std::make_unique<mbgl::style::VectorSource>(id, url);
    if (type == "raster")
      return std::make_unique<mbgl::style::RasterSource>(
        id, url, uint16_t(512)
      );
    return std::make_unique<mbgl::style::RasterDEMSource>(
      id, url, uint16_t(512)
    );
  }

  // Otherwise parse as tileset config object
  if (document.IsObject()) {
    auto tileSize = parseTileSize(document);

    // Object with "url" key = URL source with options (e.g. tileSize)
    if (document.HasMember("url") && document["url"].IsString()) {
      std::string url(
        document["url"].GetString(), document["url"].GetStringLength()
      );
      if (type == "vector")
        return std::make_unique<mbgl::style::VectorSource>(id, url);
      if (type == "raster")
        return std::make_unique<mbgl::style::RasterSource>(id, url, tileSize);
      return std::make_unique<mbgl::style::RasterDEMSource>(id, url, tileSize);
    }

    // Object with "tiles" array = tileset config
    auto tileset = parseTileset(document);
    if (type == "vector")
      return std::make_unique<mbgl::style::VectorSource>(
        id, std::move(tileset)
      );
    if (type == "raster")
      return std::make_unique<mbgl::style::RasterSource>(
        id, std::move(tileset), tileSize
      );
    return std::make_unique<mbgl::style::RasterDEMSource>(
      id, std::move(tileset), tileSize
    );
  }

  // Fallback
  if (type == "vector")
    return std::make_unique<mbgl::style::VectorSource>(id, std::string());
  if (type == "raster")
    return std::make_unique<mbgl::style::RasterSource>(
      id, std::string(), uint16_t(512)
    );
  return std::make_unique<mbgl::style::RasterDEMSource>(
    id, std::string(), uint16_t(512)
  );
}

#pragma mark - Rendering

// TODO: wrap StillImageCallback
// using StillImageCallback = std::function<void(std::exception_ptr)>;
// void renderStill(StillImageCallback);
// void renderStill(const CameraOptions&, MapDebugOptions, StillImageCallback);

void JNICALL MapLibreMap_class::triggerRepaint(JNIEnv* env, jMapLibreMap map) {
  withMapWrapper(env, map, [](auto wrapper) {
    wrapper->map->triggerRepaint();
  });
}

#pragma mark - Style

// TODO: wrap style::Style
// style::Style& getStyle();
// const style::Style& getStyle() const;
// void setStyle(std::unique_ptr<style::Style>);

void JNICALL
MapLibreMap_class::loadStyleURL(JNIEnv* env, jMapLibreMap map, jstring url) {
  withMapWrapper(env, map, [env, url](auto wrapper) {
    wrapper->map->getStyle().loadURL(smjni::java_string_to_cpp(env, url));
  });
}

void JNICALL
MapLibreMap_class::loadStyleJSON(JNIEnv* env, jMapLibreMap map, jstring json) {
  withMapWrapper(env, map, [env, json](auto wrapper) {
    wrapper->map->getStyle().loadJSON(smjni::java_string_to_cpp(env, json));
  });
}

#pragma mark - Style Layers

static std::unique_ptr<mbgl::style::Layer> createLayerByType(
  const std::string& type, const std::string& id, const std::string& sourceId
) {
  if (type == "circle")
    return std::make_unique<mbgl::style::CircleLayer>(id, sourceId);
  if (type == "line")
    return std::make_unique<mbgl::style::LineLayer>(id, sourceId);
  if (type == "fill")
    return std::make_unique<mbgl::style::FillLayer>(id, sourceId);
  if (type == "symbol")
    return std::make_unique<mbgl::style::SymbolLayer>(id, sourceId);
  if (type == "raster")
    return std::make_unique<mbgl::style::RasterLayer>(id, sourceId);
  if (type == "hillshade")
    return std::make_unique<mbgl::style::HillshadeLayer>(id, sourceId);
  if (type == "heatmap")
    return std::make_unique<mbgl::style::HeatmapLayer>(id, sourceId);
  if (type == "fill-extrusion")
    return std::make_unique<mbgl::style::FillExtrusionLayer>(id, sourceId);
  if (type == "background")
    return std::make_unique<mbgl::style::BackgroundLayer>(id);
  throw std::runtime_error("Unknown layer type: " + type);
}

void JNICALL MapLibreMap_class::addStyleLayer(
  JNIEnv* env, jMapLibreMap map, jstring type, jstring id, jstring sourceId,
  jstring beforeId
) {
  withMapWrapper(env, map, [env, type, id, sourceId, beforeId](auto wrapper) {
    auto cppType = smjni::java_string_to_cpp(env, type);
    auto cppId = smjni::java_string_to_cpp(env, id);
    auto cppSourceId = sourceId != nullptr
                         ? smjni::java_string_to_cpp(env, sourceId)
                         : std::string();

    auto layer = createLayerByType(cppType, cppId, cppSourceId);

    std::optional<std::string> before = std::nullopt;
    if (beforeId != nullptr) {
      before = smjni::java_string_to_cpp(env, beforeId);
    }
    wrapper->map->getStyle().addLayer(std::move(layer), before);
    // Clean up any stale preserved copy with this ID
    wrapper->preservedLayers.erase(cppId);
  });
}

void JNICALL
MapLibreMap_class::removeStyleLayer(JNIEnv* env, jMapLibreMap map, jstring id) {
  withMapWrapper(env, map, [env, id](auto wrapper) {
    auto cppId = smjni::java_string_to_cpp(env, id);
    auto removed = wrapper->map->getStyle().removeLayer(cppId);
    if (removed) {
      wrapper->preservedLayers[cppId] = std::move(removed);
    }
  });
}

void JNICALL MapLibreMap_class::restoreStyleLayer(
  JNIEnv* env, jMapLibreMap map, jstring id, jstring beforeId
) {
  withMapWrapper(env, map, [env, id, beforeId](auto wrapper) {
    auto cppId = smjni::java_string_to_cpp(env, id);
    auto it = wrapper->preservedLayers.find(cppId);
    if (it == wrapper->preservedLayers.end()) {
      throw std::runtime_error("No preserved layer: " + cppId);
    }
    auto layer = std::move(it->second);
    wrapper->preservedLayers.erase(it);
    std::optional<std::string> before = std::nullopt;
    if (beforeId != nullptr) {
      before = smjni::java_string_to_cpp(env, beforeId);
    }
    wrapper->map->getStyle().addLayer(std::move(layer), before);
  });
}

void JNICALL MapLibreMap_class::setStyleLayerProperty(
  JNIEnv* env, jMapLibreMap map, jstring layerId, jstring propertyName,
  jstring valueJson
) {
  withMapWrapper(
    env, map, [env, layerId, propertyName, valueJson](auto wrapper) {
      auto cppLayerId = smjni::java_string_to_cpp(env, layerId);
      auto cppName = smjni::java_string_to_cpp(env, propertyName);
      auto cppJson = smjni::java_string_to_cpp(env, valueJson);

      auto* layer = getLayerOrThrow(wrapper, cppLayerId);

      mbgl::JSDocument document;
      document.Parse<0>(cppJson.c_str());
      if (document.HasParseError()) {
        throw std::runtime_error(
          "Failed to parse property JSON: " +
          mbgl::formatJSONParseError(document)
        );
      }

      const mbgl::JSValue* value = &document;
      mbgl::style::conversion::Convertible convertible(value);
      auto setError = layer->setProperty(cppName, convertible);
      if (setError) {
        throw std::runtime_error(
          "Failed to set property '" + cppName + "': " + setError->message
        );
      }
    }
  );
}

void JNICALL MapLibreMap_class::setStyleLayerVisible(
  JNIEnv* env, jMapLibreMap map, jstring layerId, jboolean visible
) {
  withMapWrapper(env, map, [env, layerId, visible](auto wrapper) {
    auto cppLayerId = smjni::java_string_to_cpp(env, layerId);
    auto* layer = getLayerOrThrow(wrapper, cppLayerId);
    layer->setVisibility(
      visible ? mbgl::style::VisibilityType::Visible
              : mbgl::style::VisibilityType::None
    );
  });
}

void JNICALL MapLibreMap_class::setStyleLayerMinZoom(
  JNIEnv* env, jMapLibreMap map, jstring layerId, jfloat minZoom
) {
  withMapWrapper(env, map, [env, layerId, minZoom](auto wrapper) {
    auto cppLayerId = smjni::java_string_to_cpp(env, layerId);
    getLayerOrThrow(wrapper, cppLayerId)->setMinZoom(minZoom);
  });
}

void JNICALL MapLibreMap_class::setStyleLayerMaxZoom(
  JNIEnv* env, jMapLibreMap map, jstring layerId, jfloat maxZoom
) {
  withMapWrapper(env, map, [env, layerId, maxZoom](auto wrapper) {
    auto cppLayerId = smjni::java_string_to_cpp(env, layerId);
    getLayerOrThrow(wrapper, cppLayerId)->setMaxZoom(maxZoom);
  });
}

void JNICALL MapLibreMap_class::setStyleLayerSourceLayer(
  JNIEnv* env, jMapLibreMap map, jstring layerId, jstring sourceLayer
) {
  withMapWrapper(env, map, [env, layerId, sourceLayer](auto wrapper) {
    auto cppLayerId = smjni::java_string_to_cpp(env, layerId);
    auto cppSourceLayer = smjni::java_string_to_cpp(env, sourceLayer);
    getLayerOrThrow(wrapper, cppLayerId)->setSourceLayer(cppSourceLayer);
  });
}

void JNICALL MapLibreMap_class::setStyleLayerFilter(
  JNIEnv* env, jMapLibreMap map, jstring layerId, jstring filterJson
) {
  withMapWrapper(env, map, [env, layerId, filterJson](auto wrapper) {
    auto cppLayerId = smjni::java_string_to_cpp(env, layerId);
    auto cppJson = smjni::java_string_to_cpp(env, filterJson);
    auto* layer = getLayerOrThrow(wrapper, cppLayerId);

    mbgl::style::conversion::Error error;
    auto filter =
      mbgl::style::conversion::convertJSON<mbgl::style::Filter>(cppJson, error);
    if (!filter) {
      throw std::runtime_error("Failed to parse filter JSON: " + error.message);
    }
    layer->setFilter(*filter);
  });
}

#pragma mark - Style Sources

void JNICALL MapLibreMap_class::addStyleSource(
  JNIEnv* env, jMapLibreMap map, jstring type, jstring id, jstring configJson
) {
  withMapWrapper(env, map, [env, type, id, configJson](auto wrapper) {
    auto cppType = smjni::java_string_to_cpp(env, type);
    auto cppId = smjni::java_string_to_cpp(env, id);
    auto cppJson = configJson != nullptr
                     ? smjni::java_string_to_cpp(env, configJson)
                     : std::string("{}");

    std::unique_ptr<mbgl::style::Source> source;

    if (cppType == "geojson") {
      // Parse GeoJSON options from configJson if provided
      auto gjOptions = mbgl::style::GeoJSONOptions{};
      if (cppJson != "{}") {
        mbgl::JSDocument doc;
        doc.Parse<0>(cppJson.c_str());
        if (!doc.HasParseError() && doc.IsObject()) {
          auto getInt = [&doc](const char* key, int def) -> int {
            if (doc.HasMember(key))
              return static_cast<int>(getJsonNumber(doc[key], def));
            return def;
          };
          auto getBool = [&doc](const char* key, bool def) -> bool {
            if (doc.HasMember(key) && doc[key].IsBool())
              return doc[key].GetBool();
            return def;
          };
          auto getFloat = [&doc](const char* key, double def) -> double {
            if (doc.HasMember(key)) return getJsonNumber(doc[key], def);
            return def;
          };
          gjOptions.minzoom = static_cast<uint8_t>(getInt("minzoom", 0));
          gjOptions.maxzoom = static_cast<uint8_t>(getInt("maxzoom", 18));
          gjOptions.buffer = static_cast<uint16_t>(getInt("buffer", 128));
          gjOptions.tolerance = getFloat("tolerance", 0.375);
          gjOptions.cluster = getBool("cluster", false);
          gjOptions.clusterRadius =
            static_cast<uint16_t>(getInt("clusterRadius", 50));
          gjOptions.clusterMaxZoom =
            static_cast<uint8_t>(getInt("clusterMaxZoom", 17));
          gjOptions.clusterMinPoints =
            static_cast<uint8_t>(getInt("clusterMinPoints", 2));
          gjOptions.lineMetrics = getBool("lineMetrics", false);
        }
      }
      source = std::make_unique<mbgl::style::GeoJSONSource>(
        cppId,
        mbgl::Immutable<mbgl::style::GeoJSONOptions>(
          mbgl::makeMutable<mbgl::style::GeoJSONOptions>(std::move(gjOptions))
        )
      );
    } else if (cppType == "vector" || cppType == "raster" ||
               cppType == "raster-dem") {
      source = createTileSource(cppType, cppId, cppJson);
    } else if (cppType == "image") {
      source = std::make_unique<mbgl::style::ImageSource>(
        cppId, std::array<mbgl::LatLng, 4>()
      );
    } else {
      throw std::runtime_error("Unknown source type: " + cppType);
    }

    wrapper->map->getStyle().addSource(std::move(source));
  });
}

void JNICALL MapLibreMap_class::removeStyleSource(
  JNIEnv* env, jMapLibreMap map, jstring id
) {
  withMapWrapper(env, map, [env, id](auto wrapper) {
    auto cppId = smjni::java_string_to_cpp(env, id);
    wrapper->map->getStyle().removeSource(cppId);
  });
}

void JNICALL MapLibreMap_class::setStyleGeoJsonSourceData(
  JNIEnv* env, jMapLibreMap map, jstring sourceId, jstring geoJson
) {
  withMapWrapper(env, map, [env, sourceId, geoJson](auto wrapper) {
    auto cppSourceId = smjni::java_string_to_cpp(env, sourceId);
    auto cppGeoJson = smjni::java_string_to_cpp(env, geoJson);

    auto* source = wrapper->map->getStyle().getSource(cppSourceId);
    if (!source) {
      throw std::runtime_error("Source not found: " + cppSourceId);
    }
    auto* gjSource = source->template as<mbgl::style::GeoJSONSource>();
    if (!gjSource) {
      throw std::runtime_error(
        "Source is not a GeoJSON source: " + cppSourceId
      );
    }

    mbgl::style::conversion::Error error;
    auto geojson =
      mbgl::style::conversion::convertJSON<mbgl::GeoJSON>(cppGeoJson, error);
    if (!geojson) {
      throw std::runtime_error("Failed to parse GeoJSON: " + error.message);
    }
    gjSource->setGeoJSON(*geojson);
  });
}

void JNICALL MapLibreMap_class::setStyleGeoJsonSourceUrl(
  JNIEnv* env, jMapLibreMap map, jstring sourceId, jstring url
) {
  withMapWrapper(env, map, [env, sourceId, url](auto wrapper) {
    auto cppSourceId = smjni::java_string_to_cpp(env, sourceId);
    auto cppUrl = smjni::java_string_to_cpp(env, url);

    auto* source = wrapper->map->getStyle().getSource(cppSourceId);
    if (!source) {
      throw std::runtime_error("Source not found: " + cppSourceId);
    }
    auto* gjSource = source->template as<mbgl::style::GeoJSONSource>();
    if (!gjSource) {
      throw std::runtime_error(
        "Source is not a GeoJSON source: " + cppSourceId
      );
    }

    gjSource->setURL(cppUrl);
  });
}

auto JNICALL
MapLibreMap_class::hasStyleLayer(JNIEnv* env, jMapLibreMap map, jstring layerId)
  -> jboolean {
  return withMapWrapper(env, map, [env, layerId](auto wrapper) -> jboolean {
    auto cppId = smjni::java_string_to_cpp(env, layerId);
    return wrapper->map->getStyle().getLayer(cppId) != nullptr ? JNI_TRUE
                                                               : JNI_FALSE;
  });
}

auto JNICALL MapLibreMap_class::hasStyleSource(
  JNIEnv* env, jMapLibreMap map, jstring sourceId
) -> jboolean {
  return withMapWrapper(env, map, [env, sourceId](auto wrapper) -> jboolean {
    auto cppId = smjni::java_string_to_cpp(env, sourceId);
    return wrapper->map->getStyle().getSource(cppId) != nullptr ? JNI_TRUE
                                                                : JNI_FALSE;
  });
}

auto JNICALL MapLibreMap_class::getStyleLayerIds(JNIEnv* env, jMapLibreMap map)
  -> jstringArray {
  return withMapWrapper(env, map, [env](auto wrapper) -> jstringArray {
    auto layers = wrapper->map->getStyle().getLayers();
    auto result = env->NewObjectArray(
      static_cast<jsize>(layers.size()), env->FindClass("java/lang/String"),
      nullptr
    );
    for (size_t i = 0; i < layers.size(); ++i) {
      auto jId = smjni::java_string_create(env, layers[i]->getID());
      env->SetObjectArrayElement(result, static_cast<jsize>(i), jId.c_ptr());
    }
    return static_cast<jstringArray>(result);
  });
}

auto JNICALL MapLibreMap_class::getStyleSourceIds(JNIEnv* env, jMapLibreMap map)
  -> jstringArray {
  return withMapWrapper(env, map, [env](auto wrapper) -> jstringArray {
    auto sources = wrapper->map->getStyle().getSources();
    auto result = env->NewObjectArray(
      static_cast<jsize>(sources.size()), env->FindClass("java/lang/String"),
      nullptr
    );
    for (size_t i = 0; i < sources.size(); ++i) {
      auto jId = smjni::java_string_create(env, sources[i]->getID());
      env->SetObjectArrayElement(result, static_cast<jsize>(i), jId.c_ptr());
    }
    return static_cast<jstringArray>(result);
  });
}

#pragma mark - Style Images

void JNICALL MapLibreMap_class::addStyleImage(
  JNIEnv* env, jMapLibreMap map, jstring id, jint width, jint height,
  jfloat pixelRatio, jboolean sdf, jbyteArray data
) {
  withMapWrapper(
    env, map, [env, id, width, height, pixelRatio, sdf, data](auto wrapper) {
      auto cppId = smjni::java_string_to_cpp(env, id);
      auto len = env->GetArrayLength(data);
      auto expectedLen = static_cast<jsize>(width) * height * 4;
      if (len != expectedLen) {
        throw std::runtime_error(
          "Image data size mismatch: expected " + std::to_string(expectedLen) +
          " got " + std::to_string(len)
        );
      }
      auto* bytes = env->GetByteArrayElements(data, nullptr);
      if (!bytes) throw std::runtime_error("Failed to access image byte array");

      mbgl::PremultipliedImage image(
        {static_cast<uint32_t>(width), static_cast<uint32_t>(height)}
      );
      std::memcpy(image.data.get(), bytes, len);
      env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);

      wrapper->map->getStyle().addImage(
        std::make_unique<mbgl::style::Image>(
          cppId, std::move(image), pixelRatio, sdf != JNI_FALSE
        )
      );
    }
  );
}

void JNICALL
MapLibreMap_class::removeStyleImage(JNIEnv* env, jMapLibreMap map, jstring id) {
  withMapWrapper(env, map, [env, id](auto wrapper) {
    auto cppId = smjni::java_string_to_cpp(env, id);
    wrapper->map->getStyle().removeImage(cppId);
  });
}

void JNICALL MapLibreMap_class::addStyleImageStretched(
  JNIEnv* env, jMapLibreMap map, jstring id, jint width, jint height,
  jfloat pixelRatio, jboolean sdf, jbyteArray data, jfloat stretchXFrom,
  jfloat stretchXTo, jfloat stretchYFrom, jfloat stretchYTo, jfloat contentLeft,
  jfloat contentTop, jfloat contentRight, jfloat contentBottom
) {
  withMapWrapper(
    env, map,
    [env, id, width, height, pixelRatio, sdf, data, stretchXFrom, stretchXTo,
     stretchYFrom, stretchYTo, contentLeft, contentTop, contentRight,
     contentBottom](auto wrapper) {
      auto cppId = smjni::java_string_to_cpp(env, id);
      auto len = env->GetArrayLength(data);
      auto expectedLen = static_cast<jsize>(width) * height * 4;
      if (len != expectedLen) {
        throw std::runtime_error(
          "Image data size mismatch: expected " + std::to_string(expectedLen) +
          " got " + std::to_string(len)
        );
      }
      auto* bytes = env->GetByteArrayElements(data, nullptr);
      if (!bytes) throw std::runtime_error("Failed to access image byte array");

      mbgl::PremultipliedImage image(
        {static_cast<uint32_t>(width), static_cast<uint32_t>(height)}
      );
      std::memcpy(image.data.get(), bytes, len);
      env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);

      mbgl::style::ImageStretches stretchX{{stretchXFrom, stretchXTo}};
      mbgl::style::ImageStretches stretchY{{stretchYFrom, stretchYTo}};
      mbgl::style::ImageContent content{
        contentLeft, contentTop, contentRight, contentBottom
      };

      wrapper->map->getStyle().addImage(
        std::make_unique<mbgl::style::Image>(
          cppId, std::move(image), pixelRatio, sdf != JNI_FALSE,
          std::move(stretchX), std::move(stretchY), content
        )
      );
    }
  );
}

#pragma mark - ImageSource

void JNICALL MapLibreMap_class::setImageSourceCoordinates(
  JNIEnv* env, jMapLibreMap map, jstring sourceId, jdouble tlLat, jdouble tlLng,
  jdouble trLat, jdouble trLng, jdouble brLat, jdouble brLng, jdouble blLat,
  jdouble blLng
) {
  withMapWrapper(
    env, map,
    [env, sourceId, tlLat, tlLng, trLat, trLng, brLat, brLng, blLat,
     blLng](auto wrapper) {
      auto cppId = smjni::java_string_to_cpp(env, sourceId);
      auto* source = wrapper->map->getStyle().getSource(cppId);
      if (!source) throw std::runtime_error("Source not found: " + cppId);
      auto* imgSource = source->template as<mbgl::style::ImageSource>();
      if (!imgSource)
        throw std::runtime_error("Source is not an ImageSource: " + cppId);

      std::array<mbgl::LatLng, 4> coords = {
        {{tlLat, tlLng}, {trLat, trLng}, {brLat, brLng}, {blLat, blLng}}
      };
      imgSource->setCoordinates(coords);
    }
  );
}

void JNICALL MapLibreMap_class::setImageSourceImage(
  JNIEnv* env, jMapLibreMap map, jstring sourceId, jint width, jint height,
  jbyteArray data
) {
  withMapWrapper(env, map, [env, sourceId, width, height, data](auto wrapper) {
    auto cppId = smjni::java_string_to_cpp(env, sourceId);
    auto* source = wrapper->map->getStyle().getSource(cppId);
    if (!source) throw std::runtime_error("Source not found: " + cppId);
    auto* imgSource = source->template as<mbgl::style::ImageSource>();
    if (!imgSource)
      throw std::runtime_error("Source is not an ImageSource: " + cppId);

    auto len = env->GetArrayLength(data);
    auto expectedLen = static_cast<jsize>(width) * height * 4;
    if (len != expectedLen) {
      throw std::runtime_error(
        "Image data size mismatch: expected " + std::to_string(expectedLen) +
        " got " + std::to_string(len)
      );
    }
    auto* bytes = env->GetByteArrayElements(data, nullptr);
    if (!bytes) throw std::runtime_error("Failed to access image byte array");

    mbgl::PremultipliedImage image(
      {static_cast<uint32_t>(width), static_cast<uint32_t>(height)}
    );
    std::memcpy(image.data.get(), bytes, len);
    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);

    imgSource->setImage(std::move(image));
  });
}

void JNICALL MapLibreMap_class::setImageSourceUrl(
  JNIEnv* env, jMapLibreMap map, jstring sourceId, jstring url
) {
  withMapWrapper(env, map, [env, sourceId, url](auto wrapper) {
    auto cppId = smjni::java_string_to_cpp(env, sourceId);
    auto cppUrl = smjni::java_string_to_cpp(env, url);
    auto* source = wrapper->map->getStyle().getSource(cppId);
    if (!source) throw std::runtime_error("Source not found: " + cppId);
    auto* imgSource = source->template as<mbgl::style::ImageSource>();
    if (!imgSource)
      throw std::runtime_error("Source is not an ImageSource: " + cppId);

    imgSource->setURL(cppUrl);
  });
}

#pragma mark - Query Features

static std::string featuresToGeoJson(
  const std::vector<mbgl::Feature>& features
) {
  mapbox::geojson::feature_collection fc;
  fc.reserve(features.size());
  for (const auto& f : features) {
    fc.push_back(static_cast<const mbgl::GeoJSONFeature&>(f));
  }
  return mapbox::geojson::stringify(fc);
}

auto JNICALL MapLibreMap_class::querySourceFeatures(
  JNIEnv* env, jMapLibreMap map, jstring sourceId, jstring sourceLayersJson,
  jstring filterJson
) -> jstring {
  return withMapWrapper(
    env, map,
    [env, sourceId, sourceLayersJson, filterJson](auto wrapper) -> jstring {
      auto cppSourceId = smjni::java_string_to_cpp(env, sourceId);

      // Parse source layers
      std::optional<std::vector<std::string>> sourceLayers;
      if (sourceLayersJson) {
        auto cppLayersJson = smjni::java_string_to_cpp(env, sourceLayersJson);
        mbgl::JSDocument doc;
        doc.Parse<0>(cppLayersJson.c_str());
        if (!doc.HasParseError() && doc.IsArray()) {
          std::vector<std::string> layers;
          for (const auto& v : doc.GetArray()) {
            if (v.IsString())
              layers.emplace_back(v.GetString(), v.GetStringLength());
          }
          sourceLayers = std::move(layers);
        }
      }

      // Parse filter (optional)
      std::optional<mbgl::style::Filter> filter;
      if (filterJson) {
        auto cppFilterJson = smjni::java_string_to_cpp(env, filterJson);
        mbgl::style::conversion::Error error;
        auto parsed = mbgl::style::conversion::convertJSON<mbgl::style::Filter>(
          cppFilterJson, error
        );
        if (parsed) {
          filter = std::move(*parsed);
        }
      }

      auto* renderer =
        wrapper->renderer ? wrapper->renderer->getRenderer() : nullptr;
      if (!renderer) {
        return smjni::java_string_create(
                 env, "{\"type\":\"FeatureCollection\",\"features\":[]}"
        )
          .release();
      }

      mbgl::SourceQueryOptions options(
        std::move(sourceLayers), std::move(filter)
      );
      auto features = renderer->querySourceFeatures(cppSourceId, options);
      auto json = featuresToGeoJson(features);
      return smjni::java_string_create(env, json).release();
    }
  );
}

#pragma mark - Cluster Queries

auto JNICALL MapLibreMap_class::getClusterChildren(
  JNIEnv* env, jMapLibreMap map, jstring sourceId, jlong clusterId
) -> jstring {
  return withMapWrapper(
    env, map, [env, sourceId, clusterId](auto wrapper) -> jstring {
      auto cppSourceId = smjni::java_string_to_cpp(env, sourceId);

      auto* renderer =
        wrapper->renderer ? wrapper->renderer->getRenderer() : nullptr;
      if (!renderer) {
        return smjni::java_string_create(
                 env, "{\"type\":\"FeatureCollection\",\"features\":[]}"
        )
          .release();
      }

      mbgl::Feature feature;
      feature.properties["cluster_id"] = static_cast<uint64_t>(clusterId);

      auto result = renderer->queryFeatureExtensions(
        cppSourceId, feature, "supercluster", "children", {}
      );

      if (result.template is<mbgl::FeatureCollection>()) {
        auto json = mapbox::geojson::stringify(
          result.template get<mbgl::FeatureCollection>()
        );
        return smjni::java_string_create(env, json).release();
      }
      return smjni::java_string_create(
               env, "{\"type\":\"FeatureCollection\",\"features\":[]}"
      )
        .release();
    }
  );
}

auto JNICALL MapLibreMap_class::getClusterLeaves(
  JNIEnv* env, jMapLibreMap map, jstring sourceId, jlong clusterId, jlong limit,
  jlong offset
) -> jstring {
  return withMapWrapper(
    env, map,
    [env, sourceId, clusterId, limit, offset](auto wrapper) -> jstring {
      auto cppSourceId = smjni::java_string_to_cpp(env, sourceId);

      auto* renderer =
        wrapper->renderer ? wrapper->renderer->getRenderer() : nullptr;
      if (!renderer) {
        return smjni::java_string_create(
                 env, "{\"type\":\"FeatureCollection\",\"features\":[]}"
        )
          .release();
      }

      mbgl::Feature feature;
      feature.properties["cluster_id"] = static_cast<uint64_t>(clusterId);

      std::map<std::string, mbgl::Value> args = {
        {"limit", static_cast<uint64_t>(limit)},
        {"offset", static_cast<uint64_t>(offset)}
      };

      auto result = renderer->queryFeatureExtensions(
        cppSourceId, feature, "supercluster", "leaves", args
      );

      if (result.template is<mbgl::FeatureCollection>()) {
        auto json = mapbox::geojson::stringify(
          result.template get<mbgl::FeatureCollection>()
        );
        return smjni::java_string_create(env, json).release();
      }
      return smjni::java_string_create(
               env, "{\"type\":\"FeatureCollection\",\"features\":[]}"
      )
        .release();
    }
  );
}

auto JNICALL MapLibreMap_class::getClusterExpansionZoom(
  JNIEnv* env, jMapLibreMap map, jstring sourceId, jlong clusterId
) -> jint {
  return withMapWrapper(
    env, map, [env, sourceId, clusterId](auto wrapper) -> jint {
      auto cppSourceId = smjni::java_string_to_cpp(env, sourceId);

      auto* renderer =
        wrapper->renderer ? wrapper->renderer->getRenderer() : nullptr;
      if (!renderer) return 0;

      mbgl::Feature feature;
      feature.properties["cluster_id"] = static_cast<uint64_t>(clusterId);

      auto result = renderer->queryFeatureExtensions(
        cppSourceId, feature, "supercluster", "expansion-zoom", {}
      );

      if (result.template is<mbgl::Value>()) {
        auto& value = result.template get<mbgl::Value>();
        if (value.template is<uint64_t>())
          return static_cast<jint>(value.template get<uint64_t>());
        if (value.template is<int64_t>())
          return static_cast<jint>(value.template get<int64_t>());
        if (value.template is<double>())
          return static_cast<jint>(value.template get<double>());
      }
      return 0;
    }
  );
}

#pragma mark - Transitions

void JNICALL
MapLibreMap_class::cancelTransitions(JNIEnv* env, jMapLibreMap map) {
  withMapWrapper(env, map, [](auto wrapper) {
    wrapper->map->cancelTransitions();
  });
}

void JNICALL MapLibreMap_class::setGestureInProgressNative(
  JNIEnv* env, jMapLibreMap map, jboolean inProgress
) {
  withMapWrapper(env, map, [inProgress](auto wrapper) {
    wrapper->map->setGestureInProgress(inProgress != JNI_FALSE);
  });
}

auto JNICALL
MapLibreMap_class::isGestureInProgressNative(JNIEnv* env, jMapLibreMap map)
  -> jboolean {
  return withMapWrapper(env, map, [](auto wrapper) {
    return static_cast<jboolean>(wrapper->map->isGestureInProgress());
  });
}

auto JNICALL MapLibreMap_class::isRotatingNative(JNIEnv* env, jMapLibreMap map)
  -> jboolean {
  return withMapWrapper(env, map, [](auto wrapper) {
    return static_cast<jboolean>(wrapper->map->isRotating());
  });
}

auto JNICALL MapLibreMap_class::isScalingNative(JNIEnv* env, jMapLibreMap map)
  -> jboolean {
  return withMapWrapper(env, map, [](auto wrapper) {
    return static_cast<jboolean>(wrapper->map->isScaling());
  });
}

auto JNICALL MapLibreMap_class::isPanningNative(JNIEnv* env, jMapLibreMap map)
  -> jboolean {
  return withMapWrapper(env, map, [](auto wrapper) {
    return static_cast<jboolean>(wrapper->map->isPanning());
  });
}

#pragma mark - Camera

auto JNICALL MapLibreMap_class::getCameraOptions(JNIEnv* env, jMapLibreMap map)
  -> jCameraOptions {
  return withMapWrapper(env, map, [env](auto wrapper) {
    auto opts = wrapper->map->getCameraOptions();
    return maplibre_jni::convertCameraOptions(env, opts);
  });
}

void JNICALL MapLibreMap_class::jumpTo(
  JNIEnv* env, jMapLibreMap map, jCameraOptions cameraOptions
) {
  withMapWrapper(env, map, [env, cameraOptions](auto wrapper) {
    auto opts = maplibre_jni::convertCameraOptions(env, cameraOptions);
    wrapper->map->jumpTo(opts);
  });
}

void JNICALL MapLibreMap_class::easeTo(
  JNIEnv* env, jMapLibreMap map, jCameraOptions cameraOptions, jint duration
) {
  withMapWrapper(env, map, [env, cameraOptions, duration](auto wrapper) {
    auto opts = maplibre_jni::convertCameraOptions(env, cameraOptions);
    wrapper->map->easeTo(
      opts, mbgl::AnimationOptions{
              static_cast<mbgl::Duration>(std::chrono::milliseconds(duration))
            }
    );
  });
}

void JNICALL MapLibreMap_class::flyTo(
  JNIEnv* env, jMapLibreMap map, jCameraOptions cameraOptions, jint duration
) {
  withMapWrapper(env, map, [env, cameraOptions, duration](auto wrapper) {
    auto opts = maplibre_jni::convertCameraOptions(env, cameraOptions);
    wrapper->map->flyTo(
      opts, mbgl::AnimationOptions{
              static_cast<mbgl::Duration>(std::chrono::milliseconds(duration))
            }
    );
  });
}

void JNICALL MapLibreMap_class::moveBy(
  JNIEnv* env, jMapLibreMap map, jScreenCoordinate screenCoordinate
) {
  withMapWrapper(env, map, [env, screenCoordinate](auto wrapper) {
    auto coord = maplibre_jni::convertScreenCoordinate(env, screenCoordinate);
    wrapper->map->moveBy(coord);
  });
}

void JNICALL MapLibreMap_class::scaleBy(
  JNIEnv* env, jMapLibreMap map, jdouble scale, jScreenCoordinate anchor
) {
  withMapWrapper(env, map, [env, scale, anchor](auto wrapper) {
    auto anchorCoord = maplibre_jni::convertScreenCoordinate(env, anchor);
    wrapper->map->scaleBy(scale, anchorCoord);
  });
}

void JNICALL
MapLibreMap_class::pitchBy(JNIEnv* env, jMapLibreMap map, jdouble pitch) {
  withMapWrapper(env, map, [pitch](auto wrapper) {
    wrapper->map->pitchBy(pitch);
  });
}

void JNICALL MapLibreMap_class::rotateBy(
  JNIEnv* env, jMapLibreMap map, jScreenCoordinate first,
  jScreenCoordinate second
) {
  withMapWrapper(env, map, [env, first, second](auto wrapper) {
    auto firstCoord = maplibre_jni::convertScreenCoordinate(env, first);
    auto secondCoord = maplibre_jni::convertScreenCoordinate(env, second);
    wrapper->map->rotateBy(firstCoord, secondCoord);
  });
}

auto JNICALL MapLibreMap_class::cameraForLatLngBounds(
  JNIEnv* env, jMapLibreMap map, jLatLngBounds bounds, jEdgeInsets padding,
  jDouble bearing, jDouble pitch
) -> jCameraOptions {
  return withMapWrapper(
    env, map, [env, bounds, padding, bearing, pitch](auto wrapper) {
      auto cppBounds = maplibre_jni::convertLatLngBounds(env, bounds);
      auto cppPadding = maplibre_jni::convertEdgeInsets(env, padding);

      std::optional<double> cppBearing = std::nullopt;
      if (bearing != nullptr) {
        cppBearing =
          java_classes::get<Double_class>().doubleValue(env, bearing);
      }

      std::optional<double> cppPitch = std::nullopt;
      if (pitch != nullptr) {
        cppPitch = java_classes::get<Double_class>().doubleValue(env, pitch);
      }

      auto opts = wrapper->map->cameraForLatLngBounds(
        cppBounds, cppPadding, cppBearing, cppPitch
      );
      return maplibre_jni::convertCameraOptions(env, opts);
    }
  );
}

auto JNICALL MapLibreMap_class::latLngBoundsForCamera(
  JNIEnv* env, jMapLibreMap map, jCameraOptions camera
) -> jLatLngBounds {
  return withMapWrapper(env, map, [env, camera](auto wrapper) {
    auto cppCamera = maplibre_jni::convertCameraOptions(env, camera);
    auto bounds = wrapper->map->latLngBoundsForCamera(cppCamera);
    return maplibre_jni::convertLatLngBounds(env, bounds);
  });
}

auto JNICALL MapLibreMap_class::latLngBoundsForCameraUnwrapped(
  JNIEnv* env, jMapLibreMap map, jCameraOptions camera
) -> jLatLngBounds {
  return withMapWrapper(env, map, [env, camera](auto wrapper) {
    auto cppCamera = maplibre_jni::convertCameraOptions(env, camera);
    auto bounds = wrapper->map->latLngBoundsForCameraUnwrapped(cppCamera);
    return maplibre_jni::convertLatLngBounds(env, bounds);
  });
}

// TODO: wrap std::vector<LatLng>
// CameraOptions cameraForLatLngs(const std::vector<LatLng>&,
//   const EdgeInsets&,
//   const std::optional<double>& bearing = std::nullopt,
//   const std::optional<double>& pitch = std::nullopt) const;

// TODO: wrap Geometry<>
// CameraOptions cameraForGeometry(const Geometry<double>&,
//   const EdgeInsets&,
//   const std::optional<double>& bearing = std::nullopt,
//   const std::optional<double>& pitch = std::nullopt) const;

#pragma mark - Bounds

void JNICALL MapLibreMap_class::setBoundsNative(
  JNIEnv* env, jMapLibreMap map, jBoundOptions boundOptions
) {
  withMapWrapper(env, map, [env, boundOptions](auto wrapper) {
    auto opts = maplibre_jni::convertBoundOptions(env, boundOptions);
    wrapper->map->setBounds(opts);
  });
}

auto JNICALL MapLibreMap_class::getBoundsNative(JNIEnv* env, jMapLibreMap map)
  -> jBoundOptions {
  return withMapWrapper(env, map, [env](auto wrapper) {
    auto opts = wrapper->map->getBounds();
    return maplibre_jni::convertBoundOptions(env, opts);
  });
}

#pragma mark - Map Options

void JNICALL MapLibreMap_class::setNorthOrientationNative(
  JNIEnv* env, jMapLibreMap map, jNorthOrientation value
) {
  withMapWrapper(env, map, [env, value](auto wrapper) {
    jint nativeValue =
      java_classes::get<NorthOrientation_class>().getNativeValue(env, value);
    wrapper->map->setNorthOrientation(
      static_cast<mbgl::NorthOrientation>(nativeValue)
    );
  });
}

void JNICALL MapLibreMap_class::setConstrainModeNative(
  JNIEnv* env, jMapLibreMap map, jConstrainMode value
) {
  withMapWrapper(env, map, [env, value](auto wrapper) {
    jint nativeValue =
      java_classes::get<ConstrainMode_class>().getNativeValue(env, value);
    wrapper->map->setConstrainMode(
      static_cast<mbgl::ConstrainMode>(nativeValue)
    );
  });
}

void JNICALL MapLibreMap_class::setViewportModeNative(
  JNIEnv* env, jMapLibreMap map, jViewportMode value
) {
  withMapWrapper(env, map, [env, value](auto wrapper) {
    jint nativeValue =
      java_classes::get<ViewportMode_class>().getNativeValue(env, value);
    wrapper->map->setViewportMode(static_cast<mbgl::ViewportMode>(nativeValue));
  });
}

void JNICALL
MapLibreMap_class::setSize(JNIEnv* env, jMapLibreMap map, jSize size) {
  withMapWrapper(env, map, [env, size](auto wrapper) {
    auto cSize = maplibre_jni::convertSize(env, size);
    if (cSize.width > 0 && cSize.height > 0) wrapper->map->setSize(cSize);
  });
}

auto JNICALL
MapLibreMap_class::getMapOptionsNative(JNIEnv* env, jMapLibreMap map)
  -> jMapOptions {
  return withMapWrapper(env, map, [env](auto wrapper) {
    const mbgl::MapOptions opts = wrapper->map->getMapOptions();
    return maplibre_jni::convertMapOptions(env, opts);
  });
}

#pragma mark - Projection Mode

// TODO: wrap ProjectionMode
// void setProjectionMode(const ProjectionMode&);
// ProjectionMode getProjectionMode() const;

#pragma mark - Projection

auto JNICALL
MapLibreMap_class::pixelForLatLng(JNIEnv* env, jMapLibreMap map, jLatLng latLng)
  -> jScreenCoordinate {
  return withMapWrapper(env, map, [env, latLng](auto wrapper) {
    auto cLatLng = maplibre_jni::convertLatLng(env, latLng);
    return maplibre_jni::convertScreenCoordinate(
      env, wrapper->map->pixelForLatLng(cLatLng)
    );
  });
}

auto JNICALL MapLibreMap_class::latLngForPixel(
  JNIEnv* env, jMapLibreMap map, jScreenCoordinate pixel
) -> jLatLng {
  return withMapWrapper(env, map, [env, pixel](auto wrapper) {
    auto cPixel = maplibre_jni::convertScreenCoordinate(env, pixel);
    return maplibre_jni::convertLatLng(
      env, wrapper->map->latLngForPixel(cPixel)
    );
  });
}

// TODO: wrap std::vector<LatLng>
// std::vector<ScreenCoordinate> pixelsForLatLngs(const std::vector<LatLng>&)
// const; std::vector<LatLng> latLngsForPixels(const
//   std::vector<ScreenCoordinate>&) const;

#pragma mark - Transform

// TODO: wrap TransformState
// TransformState getTransfromState() const;

#pragma mark - Annotations

// TODO: wrap style::Image, Annotation, AnnotationID
// void addAnnotationImage(std::unique_ptr<style::Image>);
// void removeAnnotationImage(const std::string&);
// double getTopOffsetPixelsForAnnotationImage(const std::string&);
// AnnotationID addAnnotation(const Annotation&);
// void updateAnnotation(AnnotationID, const Annotation&);
// void removeAnnotation(AnnotationID);

#pragma mark - Tile prefetching

void JNICALL MapLibreMap_class::setPrefetchZoomDeltaNative(
  JNIEnv* env, jMapLibreMap map, jbyte delta
) {
  withMapWrapper(env, map, [delta](auto wrapper) {
    wrapper->map->setPrefetchZoomDelta(static_cast<uint8_t>(delta));
  });
}

auto JNICALL
MapLibreMap_class::getPrefetchZoomDeltaNative(JNIEnv* env, jMapLibreMap map)
  -> jbyte {
  return withMapWrapper(env, map, [](auto wrapper) {
    return static_cast<jbyte>(wrapper->map->getPrefetchZoomDelta());
  });
}

#pragma mark - Debug

void JNICALL MapLibreMap_class::setDebugNative(
  JNIEnv* env, jMapLibreMap map, jint debugOptions
) {
  withMapWrapper(env, map, [debugOptions](auto wrapper) {
    wrapper->map->setDebug(static_cast<mbgl::MapDebugOptions>(debugOptions));
  });
}

auto JNICALL MapLibreMap_class::getDebugNative(JNIEnv* env, jMapLibreMap map)
  -> jint {
  return withMapWrapper(env, map, [](auto wrapper) {
    return static_cast<jint>(wrapper->map->getDebug());
  });
}

auto JNICALL MapLibreMap_class::isRenderingStatsViewEnabledNative(
  JNIEnv* env, jMapLibreMap map
) -> jboolean {
  return withMapWrapper(env, map, [](auto wrapper) {
    return static_cast<jboolean>(wrapper->map->isRenderingStatsViewEnabled());
  });
}

void JNICALL MapLibreMap_class::enableRenderingStatsViewNative(
  JNIEnv* env, jMapLibreMap map, jboolean enabled
) {
  withMapWrapper(env, map, [enabled](auto wrapper) {
    wrapper->map->enableRenderingStatsView(enabled != JNI_FALSE);
  });
}

auto JNICALL
MapLibreMap_class::isFullyLoadedNative(JNIEnv* env, jMapLibreMap map)
  -> jboolean {
  return withMapWrapper(env, map, [](auto wrapper) {
    return static_cast<jboolean>(wrapper->map->isFullyLoaded());
  });
}

void JNICALL MapLibreMap_class::dumpDebugLogs(JNIEnv* env, jMapLibreMap map) {
  withMapWrapper(env, map, [](auto wrapper) { wrapper->map->dumpDebugLogs(); });
}

#pragma mark - Free Camera

// TODO: wrap FreeCameraOptions
// void setFreeCameraOptions(const FreeCameraOptions& camera);
// FreeCameraOptions getFreeCameraOptions() const;

#pragma mark - Tile LOD controls

void JNICALL MapLibreMap_class::setTileLodMinRadiusNative(
  JNIEnv* env, jMapLibreMap map, jdouble value
) {
  withMapWrapper(env, map, [value](auto wrapper) {
    wrapper->map->setTileLodMinRadius(value);
  });
}

auto JNICALL
MapLibreMap_class::getTileLodMinRadiusNative(JNIEnv* env, jMapLibreMap map)
  -> jdouble {
  return withMapWrapper(env, map, [](auto wrapper) {
    return wrapper->map->getTileLodMinRadius();
  });
}

void JNICALL MapLibreMap_class::setTileLodScaleNative(
  JNIEnv* env, jMapLibreMap map, jdouble value
) {
  withMapWrapper(env, map, [value](auto wrapper) {
    wrapper->map->setTileLodScale(value);
  });
}

auto JNICALL
MapLibreMap_class::getTileLodScaleNative(JNIEnv* env, jMapLibreMap map)
  -> jdouble {
  return withMapWrapper(env, map, [](auto wrapper) {
    return wrapper->map->getTileLodScale();
  });
}

void JNICALL MapLibreMap_class::setTileLodPitchThresholdNative(
  JNIEnv* env, jMapLibreMap map, jdouble value
) {
  withMapWrapper(env, map, [value](auto wrapper) {
    wrapper->map->setTileLodPitchThreshold(value);
  });
}

auto JNICALL
MapLibreMap_class::getTileLodPitchThresholdNative(JNIEnv* env, jMapLibreMap map)
  -> jdouble {
  return withMapWrapper(env, map, [](auto wrapper) {
    return wrapper->map->getTileLodPitchThreshold();
  });
}

void JNICALL MapLibreMap_class::setTileLodZoomShiftNative(
  JNIEnv* env, jMapLibreMap map, jdouble value
) {
  withMapWrapper(env, map, [value](auto wrapper) {
    wrapper->map->setTileLodZoomShift(value);
  });
}

auto JNICALL
MapLibreMap_class::getTileLodZoomShiftNative(JNIEnv* env, jMapLibreMap map)
  -> jdouble {
  return withMapWrapper(env, map, [](auto wrapper) {
    return wrapper->map->getTileLodZoomShift();
  });
}

#pragma mark - Other

// TODO: wrap ClientOptions
// ClientOptions getClientOptions() const;

// TODO: wrap ActionJournal
// const std::unique_ptr<util::ActionJournal>& getActionJournal();

#pragma mark - Allocation

auto JNICALL MapLibreMap_class::nativeInit(
  JNIEnv* env, jclass /*unused*/, jlong frontendPointer,
  jMapObserver observerObj, jMapOptions optionsObj,
  jResourceOptions resourceOptionsObj, jClientOptions clientOptionsObj
) -> jlong {
  try {
    // NOLINTNEXTLINE(cppcoreguidelines-pro-type-reinterpret-cast)
    auto* frontend =
      reinterpret_cast<maplibre_jni::CanvasRenderer*>(frontendPointer);
    auto observer = std::make_unique<maplibre_jni::JniMapObserver>(observerObj);
    mbgl::MapOptions mapOptions =
      maplibre_jni::convertMapOptions(env, optionsObj);
    mbgl::ResourceOptions resourceOptions =
      maplibre_jni::convertResourceOptions(env, resourceOptionsObj);
    mbgl::ClientOptions clientOptions =
      maplibre_jni::convertClientOptions(env, clientOptionsObj);
    auto map = std::make_unique<mbgl::Map>(
      *frontend, *observer, mapOptions, resourceOptions, clientOptions
    );

    // Get network file source for HTTP downloads
    std::shared_ptr<mbgl::FileSource> networkFileSource =
      mbgl::FileSourceManager::get()->getFileSource(
        mbgl::FileSourceType::Network, resourceOptions, clientOptions
      );

    // Get resource loader for request management
    std::shared_ptr<mbgl::FileSource> resourceLoader =
      mbgl::FileSourceManager::get()->getFileSource(
        mbgl::FileSourceType::ResourceLoader, resourceOptions, clientOptions
      );

    // Get database file source for caching
    std::shared_ptr<mbgl::FileSource> databaseFileSource =
      mbgl::FileSourceManager::get()->getFileSource(
        mbgl::FileSourceType::Database, resourceOptions, clientOptions
      );

    // NOLINTNEXTLINE(cppcoreguidelines-pro-type-reinterpret-cast)
    return reinterpret_cast<jlong>(
      new MapWrapper(map.release(), observer.release(), frontend)
    );
  } catch (const std::exception& e) {
    smjni::java_exception::translate(env, e);
    return 0;
  }
}

void JNICALL
MapLibreMap_class::nativeDestroy(JNIEnv* env, jclass /*unused*/, jlong ptr) {
  try {
    // NOLINTNEXTLINE(cppcoreguidelines-pro-type-reinterpret-cast,cppcoreguidelines-owning-memory)
    delete reinterpret_cast<MapWrapper*>(ptr);
  } catch (const std::exception& e) {
    smjni::java_exception::translate(env, e);
  }
}
