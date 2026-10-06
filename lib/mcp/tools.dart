import 'dart:convert';

import '../action/command_handler.dart';
import '../action/commands.dart';
import '../action/queries.dart';
import '../data/repository.dart';
import '../service/weather.dart';
import '../util/schedule_day.dart';
import 'jsonrpc.dart';

/// MCP 工具面（schedule-app.md §6，十个，MVP 定格不增不减）：
/// list_plans / add_plan / update_plan / get_settings / update_settings /
/// update_fixed_slots / propose_schedule / adjust_blocks / get_schedule / get_history。
///
/// 本层只做三件事，**不做任何业务判断**（防呆全在命令层，模式照抄拾贝 tools.dart）：
/// ① 把大模型输出的 JSON 反序列化成 [ScheduleCommand]（与 UI 组装的同一类对象，
///    经 [ScheduleCommand.fromJson] 单一入口）；
/// ② 以 [CommandActor.ai] 调用命令层；
/// ③ 把 [CommandResult]（含最新快照）序列化回 JSON，让大模型上下文与数据库对齐。
/// 错误转换保留 [ActionException.data]：version_conflict 的 latest 快照、
/// schedule_rejected 的逐条明细 + available_free_windows 原样到达 AI（§6 返回契约）。
List<Map<String, Object?>> toolSchemas() => [
      {
        'name': 'list_plans',
        'description':
            '列出清单（愿望池/推进中/待安排的统一视图）。默认只返回未归档计划——'
                '归档与冷数据不拉，防上下文膨胀；需要时用 include_archived 显式放开。',
        'inputSchema': {
          'type': 'object',
          'properties': {
            'include_archived': {'type': 'boolean', 'default': false, 'description': '是否包含已归档计划'},
            'parent_id': {'type': 'string', 'description': '按父计划过滤（看子树），可省略'},
          },
        },
      },
      {
        'name': 'add_plan',
        'description':
            '新建计划（澄清/拆解/大脑倾倒的落库入口）。title 是用户可读的条目名；'
                'spec 是你维护的精确无歧义描述；澄清得到的未决问题放 open_items 不阻塞建档。'
                '返回新计划快照。',
        'inputSchema': {
          'type': 'object',
          'properties': {
            'title': {'type': 'string', 'description': '计划标题（用户随手打的原始名，原样保留）'},
            'spec': {'type': 'string', 'description': '精确无歧义描述（你的唯一权威陈述），可省略'},
            'notes': {'type': 'string', 'description': '已敲定细节沉淀；长任务请在首行写启动第一步', },
            'min_viable_action': {'type': 'string', 'description': '降级行动（「哪怕只读 1 页」），建议给出'},
            'energy_level': {'type': 'string', 'enum': ['deep', 'light'], 'description': '默认 light'},
            'tool_required': {'type': 'string', 'enum': ['desk', 'anywhere'], 'description': '默认 anywhere'},
            'reward_spec': {'type': 'string', 'description': '犒赏锚点（高阻力付出型计划收尾收集）'},
            'importance': {'type': 'boolean', 'description': '重要标记（重要+不紧急=黄金象限，AI 主动圈地）'},
            'deadline': {'type': 'string', 'description': '截止日 YYYY-MM-DD，可省略'},
            'estimate': {'type': 'integer', 'description': '预估时长（分钟）'},
            'parent_id': {'type': 'string', 'description': '父计划 id（拆解挂子计划用）'},
            'open_items': {
              'type': 'array',
              'items': {
                'type': 'object',
                'properties': {
                  'question': {'type': 'string'},
                  'answer': {'type': 'string'},
                },
                'required': ['question'],
              },
              'description': '澄清中未敲定的问题（answer 留空）；人在 app 内可手答',
            },
          },
          'required': ['title'],
        },
      },
      {
        'name': 'update_plan',
        'description':
            '字段级修改计划（澄清答案回写 spec/notes、敲定 min_viable_action 等）。'
                '只发要改的字段；带 expected_version 做乐观锁，冲突时错误体直接给 latest 快照——'
                '在快照上合并你的修改重试即可，不要重新 list_plans。',
        'inputSchema': {
          'type': 'object',
          'properties': {
            'id': {'type': 'string', 'description': '计划 uuid'},
            'expected_version': {'type': 'integer', 'description': '你看到的 version（乐观锁断言）'},
            'title': {'type': 'string'},
            'spec': {'type': 'string'},
            'notes': {'type': 'string'},
            'min_viable_action': {'type': 'string'},
            'energy_level': {'type': 'string', 'enum': ['deep', 'light']},
            'tool_required': {'type': 'string', 'enum': ['desk', 'anywhere']},
            'reward_spec': {'type': 'string'},
            'importance': {'type': 'boolean'},
            'deadline': {'type': 'string'},
            'estimate': {'type': 'integer'},
            'parent_id': {'type': 'string'},
            'open_items': {
              'type': 'array',
              'items': {
                'type': 'object',
                'properties': {
                  'question': {'type': 'string'},
                  'answer': {'type': 'string'},
                },
                'required': ['question'],
              },
              'description': '澄清中未敲定的问题（answer 留空）；人在 app 内可手答',
            },
            'archived': {'type': 'boolean', 'description': '归档（清单治理用）'},
          },
          'required': ['id'],
        },
      },
      {
        'name': 'get_settings',
        'description':
            '读用户设置与一周节奏：作息边界（wake/sleep 分钟数）、today_energy 三档电量、'
                'user_rules 显式偏好（优先级高于统计推断，必须遵守）、单日排量上限、'
                'fixed_slots 固定占用与当前日期。initialized=false 表示尚未完成首启引导，'
                '此时 propose_schedule 会被整单硬拒。',
        'inputSchema': {'type': 'object', 'properties': {}},
      },
      {
        'name': 'update_settings',
        'description':
            '写用户设置（键注册制，只发要改的键）。典型：user_rules（用户明说的偏好如'
                '「周五晚上不排深度工作」）、today_energy、weather_location。传 null 清空对应键。',
        'inputSchema': {
          'type': 'object',
          'properties': {
            'wake_time': {'type': 'integer', 'description': '起床分钟数 0..1439'},
            'sleep_time': {'type': 'integer', 'description': '睡觉分钟数 0..1439（小于 wake 即跨午夜睡眠）'},
            'min_block_minutes': {'type': 'integer', 'description': '最小块粒度（分钟）'},
            'daily_new_blocks_limit': {'type': 'integer', 'description': '单日新增排量上限（块数）'},
            'today_energy': {'type': 'string', 'enum': ['high', 'normal', 'low'], 'description': '今日生理电量三档'},
            'user_rules': {'type': 'string', 'description': '显式偏好文本'},
            'weather_location': {'type': 'string', 'description': '天气城市（手动填，零定位权限）'},
            'exceptions': {
              'type': 'array',
              'items': {'type': 'object'},
              'description': '例外日 [{start,end,label}]（假期与出行）',
            },
          },
        },
      },
      {
        'name': 'update_fixed_slots',
        'description':
            '批量原子替换一周节奏（固定占用模板）：整批成功或整批不动。'
                'weekdays 是 ISO 星期数组（1=周一…7=周日），工作日/周末模板=不同 weekday 集合并存；'
                'end_min < start_min 表示跨午夜（如睡眠 1410→450）；空数组合法（自由职业）。',
        'inputSchema': {
          'type': 'object',
          'properties': {
            'slots': {
              'type': 'array',
              'items': {
                'type': 'object',
                'properties': {
                  'name': {'type': 'string'},
                  'weekdays': {'type': 'array', 'items': {'type': 'integer'}},
                  'start_min': {'type': 'integer'},
                  'end_min': {'type': 'integer'},
                },
                'required': ['name', 'weekdays', 'start_min', 'end_min'],
              },
            },
          },
          'required': ['slots'],
        },
      },
      {
        'name': 'propose_schedule',
        'description':
            '按天整表原子提案（你的核心排程动作）：全部合法才落库为 proposed 块，'
                '否则整单拒绝并逐条返回原因 + available_free_windows。'
                '调用前必须先 get_schedule 读当天分布（先读后排）；只排明确态的叶子行动；'
                'is_day_spark 指定当天最重要的一件事（至多一个）；你的提案只替换自己的未确认块，'
                'human/pinned 块绕行。人确认后才生效。',
        'inputSchema': {
          'type': 'object',
          'properties': {
            'date': {'type': 'string', 'description': '目标日 YYYY-MM-DD（用 get_schedule 返回的日期口径，不要自己算）'},
            'items': {
              'type': 'array',
              'items': {
                'type': 'object',
                'properties': {
                  'plan_id': {'type': 'string', 'description': '关联计划 id（孤立块可省略）'},
                  'label': {'type': 'string', 'description': '行动描述（动词开头，精确到这次做什么）'},
                  'start_min': {'type': 'integer', 'description': '起始分钟 0..1439'},
                  'end_min': {'type': 'integer', 'description': '结束分钟 1..1440（1440=24:00）'},
                  'is_day_spark': {'type': 'boolean', 'description': '当日黄金火种（至多一个）'},
                  'is_celebration': {'type': 'boolean', 'description': '庆祝块（犒赏兑现）'},
                },
                'required': ['start_min', 'end_min'],
              },
            },
          },
          'required': ['date', 'items'],
        },
      },
      {
        'name': 'adjust_blocks',
        'description':
            '按 id 建/挪/缩/删单个块（火车行程等硬日程的入口；受和平条款门控：'
                'human 块与 pinned 块不可动）。add 默认 pinned=true（硬行程对 AI 自己也是墙）；'
                'remove 对未确认块=撤回，对已确认块=melted 中性蒸发回愿望池（无痕）。',
        'inputSchema': {
          'type': 'object',
          'properties': {
            'action': {'type': 'string', 'enum': ['add', 'move', 'resize', 'remove']},
            'block_id': {'type': 'string', 'description': 'move/resize/remove 必填'},
            'date': {'type': 'string', 'description': 'add 必填；move 可选（跨日改期）'},
            'start_min': {'type': 'integer', 'description': 'add 必填；move/resize 至少给其一'},
            'end_min': {'type': 'integer', 'description': 'add 必填；move/resize 至少给其一'},
            'plan_id': {'type': 'string', 'description': 'add 可选'},
            'label': {'type': 'string', 'description': 'add 可选（动词开头的行动描述）'},
            'pinned': {'type': 'boolean', 'description': 'add 可选，默认 true'},
            'expected_version': {'type': 'integer', 'description': 'move/resize/remove 乐观锁'},
          },
          'required': ['action'],
        },
      },
      {
        'name': 'get_schedule',
        'description':
            '读今天+明天（可选 days≤3 拉长）的日程视图：块（含 effective_label=块 label 缺省回落计划标题）、'
                'fixed_slots（当天开始+前夜溢出段）。日期由服务端供——「今天」按作息日切割，'
                '不要自己算日期。排程前的必读动作（先读后排）。',
        'inputSchema': {
          'type': 'object',
          'properties': {
            'days': {'type': 'integer', 'default': 2, 'maximum': 3, 'description': '视窗天数 1..3'},
          },
        },
      },
      {
        'name': 'get_history',
        'description':
            '近 N 天（默认 14）完成情况聚合：按日 done 数/分钟/火种达成 + 总计。'
                '恒为聚合体、永不返回明细行。用于校准时长估计、复盘推进节奏（M3 起含深潜净值等校准指标）。',
        'inputSchema': {
          'type': 'object',
          'properties': {
            'days': {'type': 'integer', 'default': 14, 'maximum': 90},
          },
        },
      },
    ];

Future<List<Map<String, Object?>>> callTool(
  String name,
  Map<String, Object?> args,
  Repository repo,
) async {
  final handler = CommandHandler(repo);
  final queries = ScheduleQueries(repo);
  switch (name) {
    case 'list_plans':
      final plans = await repo.listPlans(
        includeArchived: args['include_archived'] == true,
        parentId: _str(args['parent_id']),
      );
      return [
        _text(jsonEncode({
          'count': plans.length,
          'plans': [for (final p in plans) planToJson(p)],
        })),
      ];

    case 'add_plan':
      final r = await _guarded(() => handler.execute(
            ScheduleCommand.fromJson({'op': 'upsert_plan', ...args}),
            actor: CommandActor.ai,
          ));
      return [_text(jsonEncode(r.toJson()))];

    case 'update_plan':
      final r = await _guarded(() => handler.execute(
            ScheduleCommand.fromJson({'op': 'update_plan', ...args}),
            actor: CommandActor.ai,
          ));
      return [_text(jsonEncode(r.toJson()))];

    case 'get_settings':
      final settings = await queries.getSettings();
      final fixedSlots = await repo.listFixedSlots();
      final wake = settings['wake_time'] as int? ?? 0;
      final today = isoDate(scheduleDayOf(DateTime.now(), wake));
      return [
        _text(jsonEncode({
          ...settings,
          'today': today,
          'fixed_slots': [for (final s in fixedSlots) slotToJson(s)],
        })),
      ];

    case 'update_settings':
      final r = await _guarded(() => handler.execute(
            ScheduleCommand.fromJson({'op': 'update_settings', 'values': args}),
            actor: CommandActor.ai,
          ));
      return [_text(jsonEncode(r.toJson()))];

    case 'update_fixed_slots':
      final r = await _guarded(() => handler.execute(
            ScheduleCommand.fromJson({'op': 'update_fixed_slots', ...args}),
            actor: CommandActor.ai,
          ));
      return [_text(jsonEncode(r.toJson()))];

    case 'propose_schedule':
      final r = await _guarded(() => handler.execute(
            ScheduleCommand.fromJson({'op': 'propose_schedule', ...args}),
            actor: CommandActor.ai,
          ));
      return [_text(jsonEncode(r.toJson()))];

    case 'adjust_blocks':
      final r = await _guarded(() => handler.execute(
            ScheduleCommand.fromJson({'op': 'adjust_blocks', ...args}),
            actor: CommandActor.ai,
          ));
      return [_text(jsonEncode(r.toJson()))];

    case 'get_schedule':
      final days = _clampInt(args['days'], 2, 1, 3);
      final schedule = await queries.getSchedule(days: days);
      // 天气投影搭载（§11 拍板：视窗与预报天然同窗，propose 前必读使天气自动
      // 到达 AI 视线；获取失败静默降级——返回体不携带，AI 可改口问用户）
      final settings = await queries.getSettings();
      final location = settings['weather_location'] as String?;
      if (location != null && location.isNotEmpty) {
        final wf = await weatherService.forecast3(location);
        if (wf != null) schedule['weather'] = wf;
      }
      return [_text(jsonEncode(schedule))];

    case 'get_history':
      final days = _clampInt(args['days'], 14, 1, 90);
      return [_text(jsonEncode(await queries.getHistory(days: days)))];

    default:
      throw McpRpcError(errInvalidParams, 'Unknown tool: $name');
  }
}

/// 命令层异常 → MCP 错误：领域拒绝码/hint/结构化 data 全量透传
/// （version_conflict 回 latest 快照、schedule_rejected 回明细+空窗，§6）。
Future<T> _guarded<T>(Future<T> Function() run) async {
  try {
    return await run();
  } on ActionException catch (e) {
    throw McpRpcError(errInvalidParams, e.message, {
      'code': e.code,
      'hint': ?e.hint,
      ...?e.data,
    });
  }
}

String? _str(Object? v) => v is String && v.isNotEmpty ? v : null;

int _clampInt(Object? v, int dflt, int min, int max) {
  final n = v is int ? v : (v is String ? int.tryParse(v) : null);
  if (n == null) return dflt;
  return n < min ? min : (n > max ? max : n);
}

Map<String, Object?> _text(String s) => {'type': 'text', 'text': s};
