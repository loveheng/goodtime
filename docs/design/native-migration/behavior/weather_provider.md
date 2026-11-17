---
status: active
updated: 2026-11-15
---

# 行为清单 · 天气投影（P3④，待人工过目）

> §9.1.6 模板产出（2026-11-15 晨间补走抽取，抽取源=lib/service/weather.dart 99 行 + lib/mcp/weather_provider.dart 端口）。
> 设计定位（schedule-app §11 拍板）：无 key JSON 源（Open-Meteo）+ 内存 TTL 缓存不落库；经 get_schedule 返回体搭载三日结构化预报；**机械层对天气无感**——天气只进提案推理与态势句；获取失败静默降级（AI 可改口问用户）。

## 函数注册表

| 方法 | 行为 |
|---|---|
| `forecast3(city, now?)` | 城市空/全空白 → null；TTL 缓存命中（同 city 且 now−cacheAt < 1h）→ 直返缓存；未命中走两段 API（见下）；装配 `{city, days[≤3]: {date, summary, t_max, t_min, precip_prob}}`；**任一步失败/非 200/解析失败 → null 静默降级（不抛，失败不缓存）** |

**两段 API（逐字）**：
1. 地理编码 `https://geocoding-api.open-meteo.com/v1/search?name=<urlencode(city)>&count=1&language=zh&format=json` → `results` 非数组或空 → null；取 `results[0].latitude/longitude`
2. 预报 `https://api.open-meteo.com/v1/forecast?latitude=<lat>&longitude=<lon>&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max&forecast_days=3&timezone=auto` → `daily` 非对象 → null；`daily.time`（字符串数组）与 `daily.weather_code`（数值数组）逐日装配 ≤3 天：summary=`describeCode(code[i])`，t_max/t_min/precip_prob 原样透传（数组缺位/null 透传 null）

**WMO 天气码映射（describeCode，逐字）**：`<0`→未知｜`0`→晴｜`≤3`→多云｜`45,48`→雾｜`51..67`→雨｜`71..77`→雪｜`80..82`→阵雨｜`≥95`→雷雨｜其余→多变

## 平移注记（待实现要点）

- Kotlin 落点=**:app `OkWeatherProvider`**（OkHttp——:core:model relay 族已引 OkHttp 依赖树，:app 直用）；实现 NativeToolFace 构造参 `weather: (suspend (location: String) -> JsonObject?)?` 钩子签名（返回 JsonObject? 同形）。
- TTL 缓存：进程内单例字段（city/at/data 三元组），**不落库不持久化**——重启/日变更高即重读（§11 拍板逐字）；DEGRADE 标记保留 `[weather_unavailable]`（静默降级语义，ai-sideeffect-guard 口径）。
- AppContainer 接线：`NativeToolFace(repo, weather = OkWeatherProvider())` 替换现 `weather=null`；weather_location 设置为空时钩子不触发（NativeToolFace 既有判定）。
- 缓存键=用户输入 city 原串（trim 前）？——**否，trim 后非空串为键**（命中判定 `_cacheCity == city`，Dart 逐字：city 参数未 trim 就比——实现按原字面保留，不归一）。
