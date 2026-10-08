import 'dart:convert';

import '../action/command_handler.dart';
import '../action/commands.dart';
import '../action/queries.dart';
import '../data/repository.dart';
import '../models/artifact.dart';
import '../models/background.dart';
import '../service/weather.dart';
import '../util/schedule_day.dart';
import '../data/settings.dart';
import 'jsonrpc.dart';

/// MCP 工具面（schedule-app.md §6 原十排程工具 + suggest_user_setting 建言通道 +
/// 背景双工具 upsert_background/merge_backgrounds（背景草案 §2/§4）+
/// upsert_facts 硬事实通道（fact-user-relay-draft.md §8-4，2026-10-07））。
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
                '归档与冷数据不拉，防上下文膨胀；需要时用 include_archived 显式放开。'
                '每个计划快照携带其直属背景（backgrounds，含作用日期窗）——分解/澄清时'
                '先读，别让「妈妈膝盖不好」这类约束从你眼前溜走。',
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
            '写用户设置（可改键：wake_time/sleep_time/min_block_minutes/daily_new_blocks_limit/'
                'fill_rate_limit/today_energy，AI 可调；today_energy 为今日电量档 high/normal/low，'
                'AI 据今日负荷判断后置）。仅用户自设、AI 不可改的键：user_rules/weather_location/'
                'exceptions/identity_prompt/theme_mode——若你判断这些键需要调整，请勿经此工具改动'
                '（会被拒），请在回复中明确建议用户前往 app「设置」页手动修改。传 null 清空对应键。',
        'inputSchema': {
          'type': 'object',
          'properties': {
            'wake_time': {'type': 'integer', 'description': '起床分钟数 0..1439'},
            'sleep_time': {'type': 'integer', 'description': '睡觉分钟数 0..1439（小于 wake 即跨午夜睡眠）'},
            'min_block_minutes': {'type': 'integer', 'description': '最小块粒度（分钟）'},
            'daily_new_blocks_limit': {'type': 'integer', 'description': '单日新增排量上限（块数）'},
            'fill_rate_limit': {'type': 'integer', 'description': '填充率上限（百分比 5..100，默认 60）'},
            'today_energy': {
              'type': 'string',
              'enum': ['high', 'normal', 'low'],
              'description': '今日生理电量三档，AI 据今日负荷/状态判断后置（开放 AI 修正）'
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
                'human/pinned 块绕行。为视窗外远期行程排程时，get_schedule 的 upcoming_facts '
                '锚点时间是硬约束，提案不得与之冲突。人确认后才生效。',
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
                'fixed_slots（当天开始+前夜溢出段）、facts=当日结构化凭证摘要（发车/止检/止入场等硬约束'
                '自动到达你的视线，随视窗逐日搭载；按计划分组，unattributed=未归属凭证——提案必须避开'
                '锚点时刻并为赶路留缓冲）。upcoming_facts=视窗（3 天）之外未来 ~14 天的结构化凭证'
                '轻量摘要（date/title/deadline_min/plan_title）——为远期行程（车票/门诊/门票）做规划时'
                '这些锚点时间是硬约束，提案不得与之冲突；过远冷数据不搭载。'
                '日期由服务端供——「今天」按作息日切割，'
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
      {
        'name': 'suggest_user_setting',
        'description':
            '当你判断某个仅用户自设的设置键（user_rules/weather_location/exceptions/'
                'identity_prompt/theme_mode）确需调整时，用此工具把建议推送给 app，'
                '由用户在手机端「设置」页手动决断——你无权经 update_settings 改这些键，不要尝试写入，只在此建言。',
        'inputSchema': {
          'type': 'object',
          'required': ['key', 'reason'],
          'properties': {
            'key': {
              'type': 'string',
              'enum': ['user_rules', 'weather_location', 'exceptions', 'identity_prompt', 'theme_mode'],
              'description': '建议用户修改的设置键（仅用户自设键）'
            },
            'reason': {'type': 'string', 'description': '建议原因（简明、具体）'},
            'suggested_value': {'type': 'string', 'description': '建议的目标值（仅供参考，由用户决定是否采纳）'},
            'severity': {'type': 'string', 'enum': ['low', 'normal', 'high'], 'description': '建议紧急度，默认 normal'},
          },
        },
      },
      {
        'name': 'upsert_background',
        'description':
            '写入用户背景信息（软上下文）：叙事性、定性的行程背景——「陪父母看诊顺带旅游」'
                '「妈妈膝盖不好少走路」，与硬事实（车票/预约）相对：无刚性时空边界，只影响你的'
                '提案权重与提案理由（理由区须显式引用哪条背景），绝不生成硬约束。'
                'id 省略=建档；给定且存在=编辑（仅 content/tags/applicable_dates 可改；'
                'scope/plan_id 不可变，raw_source_text 永不变——传入新原话被忽略，不要重试覆盖）。'
                'scope=global 常驻画像叙事（过敏原/常住地/体力常态）受注入预算终态双指标：'
                '条数≤8 且 content 总字数≤400（只计 content，raw/tags 不计）——超限返回 '
                'budget_exceeded 且错误体附当前全量现场，你应向用户发起具体归并询问后走 '
                'merge_backgrounds，不要原样重试；scope=plan 行程背景挂计划，无预算。'
                'applicable_dates=瞬态背景作用日期窗：单日 ISO 日期字符串数组（一律日历日，如 '
                '["2026-10-08","2026-10-09"]），缺省=长期有效，空数组按长期处理；窗口超过 14 天'
                '请改为长期背景（瞬态窗本就该短）。写完必须向用户显式回显（「已记录背景：xxx——'
                '不对就说我改」）。三通道路由：人设语气→identity_prompt（走建言）、硬性常驻规则→'
                'user_rules（同样建言）、画像叙事→本工具。',
        'inputSchema': {
          'type': 'object',
          'properties': {
            'id': {'type': 'string', 'description': '背景 uuid（省略=建档；给定且存在=编辑）'},
            'scope': {
              'type': 'string',
              'enum': ['global', 'plan'],
              'description': 'global=常驻画像叙事（受预算终态双指标） / plan=行程背景（无预算）'
            },
            'plan_id': {'type': 'string', 'description': 'scope=plan 必填（行程背景挂在计划上）'},
            'content': {'type': 'string', 'description': '给人与 AI 读的归纳描述（一条记录=一个可独立成立、可独立删除的语义；预算只计本字段字数）'},
            'raw_source_text': {'type': 'string', 'description': '建档原话逐字（仅建档写入一次；编辑路径忽略）'},
            'tags': {'type': 'array', 'items': {'type': 'string'}, 'description': '自由标签（#健康 式，不锁枚举）'},
            'applicable_dates': {
              'type': 'array',
              'items': {'type': 'string'},
              'description': '瞬态作用日期窗：单日 ISO 日期数组（一律日历日）；缺省=长期；超 14 天改长期',
            },
          },
          'required': ['scope', 'content'],
        },
      },
      {
        'name': 'merge_backgrounds',
        'description':
            '背景治理：归并/清理一批原子执行（ops 数组单事务，终态校验全过才落库，'
                '否则整批拒且原样回滚）。budget_exceeded 后用此工具执行你的归并方案：'
                '先向用户发起具体归并询问（「『常住深圳』建议与『珠三角周末游』合并，可以吗？」），'
                '确认后再发 ops。删除不存在 id 或终态仍超预算都会整批拒（错误体附当前现场），'
                '以现场为准重发，不要盲目重试。',
        'inputSchema': {
          'type': 'object',
          'properties': {
            'ops': {
              'type': 'array',
              'description': '原子批：如 删 A / 删 B / 增 C',
              'items': {
                'type': 'object',
                'properties': {
                  'kind': {'type': 'string', 'enum': ['delete', 'upsert']},
                  'id': {'type': 'string', 'description': 'delete 必填；upsert 给 id 且行存在=编辑、否则=建档'},
                  'scope': {'type': 'string', 'enum': ['global', 'plan'], 'description': '建档必填'},
                  'plan_id': {'type': 'string', 'description': '建档 scope=plan 必填'},
                  'content': {'type': 'string', 'description': 'upsert 必填（归并后的归纳描述）'},
                  'raw_source_text': {'type': 'string', 'description': '仅建档生效'},
                  'tags': {'type': 'array', 'items': {'type': 'string'}},
                  'applicable_dates': {'type': 'array', 'items': {'type': 'string'}},
                },
                'required': ['kind'],
              },
            },
          },
          'required': ['ops'],
        },
      },
      {
        'name': 'upsert_facts',
        'description':
            '写入硬事实凭证（车票/门票/酒店/场馆通知/口信——有刚性时空边界或到场约束的'
                '事实，与软背景相对）：你是唯一解析器，端侧零解析。id 省略=建档（对话投喂'
                '直接 state=structured；系统分享/快记原文已由 app 以 verbal/verbal 占位'
                '入册为 raw，你在下一轮读 raw_text 提炼回填同 id——出生确认：该次回填'
                '由你定 category/source_kind 终值，之后二值恒不可变）。id 给定=编辑'
                '（字段级 merge 只覆盖传入槽位：'
                '提炼回填 state=structured / 改挂 plan_id / 作废 state=voided——退票/取消'
                '须先获用户同意；raw_text 传入不同原文时自动追加双段只增不清，不要重发'
                '相同原文）。time_anchors 三类锚：moment（时刻：发车/止检，date+min）、'
                'span（区段：乘车/入住，date+start_min+end_min，跨日加 end_date）、'
                'rule（长期规则：周一闭馆）。badge 仅 booking 类显式给出（「05车12F」式'
                '单值微标，超 12 字符自动截断）；hero_metrics ≤3 组 {k,v}（超 3 自动截前 3）；'
                '低置信槽位宁可缺省不猜。三刀路由：有刚性时空边界→本工具；需用户做一次'
                '行动→add_plan 或等待锚点；到场遵守/携带/注意→constraints 三数组'
                '（required_items 要求/rules 禁止/notices 提示）；叙事性软上下文→'
                'upsert_background。物理删除仅用户可做，你无权删除、只可提议。'
                '写完必须向用户显式回显（「已记录凭证：xxx——不对就说我改」）。',
        'inputSchema': {
          'type': 'object',
          'properties': {
            'id': {'type': 'string', 'description': '凭证 uuid（省略=建档；给定且存在=编辑）'},
            'category': {
              'type': 'string',
              'enum': ['transit', 'ticket', 'hotel', 'venue', 'verbal'],
              'description': '交通/门票/住宿/须知/口头信息 五类收敛'
            },
            'source_kind': {
              'type': 'string',
              'enum': ['booking', 'announcement', 'verbal'],
              'description': 'booking=官方预订凭证（可亮 hero+上时间轴）/ announcement=官方公告政策 / verbal=口头转述（永不硬拦）'
            },
            'state': {
              'type': 'string',
              'enum': ['raw', 'structured', 'voided'],
              'description': '建档缺省 raw；提炼回填=structured；作废（退票/取消，须用户同意）=voided。AI 对话投喂结构化凭证（车票/门票等，能定类别与锚点）请显式传 structured，一步到位'
            },
            'title': {'type': 'string', 'description': '摘要标题（如「大理→丽江 动车 D8724」）'},
            'origin': {
              'type': 'string',
              'enum': ['shared', 'quicknote', 'ai', 'manual'],
              'description': '出生来源（建档必填）：shared=系统分享 / quicknote=快记粘贴 / ai=对话投喂'
            },
            'badge': {'type': 'string', 'description': '时间轴微标单值（05车12F；仅 booking 类；超 12 字符自动截断）'},
            'hero_metrics': {
              'type': 'array',
              'items': {
                'type': 'object',
                'properties': {'k': {'type': 'string'}, 'v': {'type': 'string'}},
                'required': ['k', 'v'],
              },
              'description': '通关区 KV ≤3 组（车厢座位/检票口/预约码），超 3 自动截前 3'
            },
            'time_anchors': {
              'type': 'array',
              'items': {
                'type': 'object',
                'properties': {
                  'role': {'type': 'string', 'description': '锚角色（发车/停止检票/入住）'},
                  'kind': {'type': 'string', 'enum': ['moment', 'span', 'rule']},
                  'date': {'type': 'string', 'description': 'YYYY-MM-DD 合法日历日'},
                  'min': {'type': 'integer', 'description': 'moment 时刻分钟 0..1439'},
                  'start_min': {'type': 'integer', 'description': 'span 起始分钟'},
                  'end_min': {'type': 'integer', 'description': 'span 结束分钟'},
                  'end_date': {'type': 'string', 'description': 'span 跨日结束日（缺省=单日）'},
                },
                'required': ['kind'],
              },
              'description': '时空锚数组：事实存锚点不存区段，驱动块生成/止检红线/一致性比对'
            },
            'constraints': {
              'type': 'object',
              'description': '三数组 required_items（要求带/做）/rules（禁止）/notices（提示）+机读参数 advance_arrival_minutes/forbidden_weekdays/daily_deadline_min',
            },
            'location': {'type': 'string', 'description': '地点（检票口/场馆地址；UI 转 geo: 导航）'},
            'copyable_code': {'type': 'string', 'description': '订单号/预约码（UI 一键复制）'},
            'contact_phone': {'type': 'string', 'description': '联系电话（UI 转 tel: 拨号）'},
            'raw_text': {'type': 'string', 'description': '原文逐字永存（编辑传入不同原文=追加双段，不重发相同原文）'},
            'plan_id': {'type': 'string', 'description': '归属计划（编辑给定=改挂；缺省=未归属池）'},
            'block_id': {'type': 'string', 'description': '升格生成的关联块 id（一致性比对/🎫 微标数据源）'},
            'expected_version': {'type': 'integer', 'description': '你看到的 version（编辑乐观锁，冲突回 latest）'},
          },
          'required': ['category', 'source_kind'],
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
      // raw 凭证摘要搭载（fact 草案 §1.6/§1.7 消化 SOP）：「N 条原文待提炼」——
      // 逐条读 raw_text 解析后经 upsert_facts 回填同 id（state=structured）
      final rawFacts = await repo.artifactsByState(Artifact.stateRaw);
      return [
        _text(jsonEncode({
          'count': plans.length,
          'raw_facts': {
            'count': rawFacts.length,
            'items': [
              for (final f in rawFacts)
                {
                  'id': f.id,
                  'title': f.title,
                  'plan_id': ?f.planId,
                  'raw_text': ?f.payload['raw_text'],
                },
            ],
          },
          'plans': [
            for (final p in plans)
              {
                ...planToJson(p),
                // plan 快照携 plan 背景（背景草案 §4 搭载模式）：仅本计划直属、
                // 日期窗原样随行——祖先继承与当日过滤在 get_schedule 排程装配
                'backgrounds': [
                  for (final b in await repo.listBackgrounds(
                      scope: Background.scopePlan, planId: p.id!))
                    backgroundToJson(b),
                ],
              },
          ],
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
      // 全局背景治理装配搭载（背景草案 §4 治理/对话通道：全量带 [已过期] 标 +
      // 预算态——AI 能自测才不会反复撞墙；排程通道的物理过滤在 get_schedule 另行装配）
      final backgrounds = await queries.globalBackgroundsPayload();
      return [
        _text(jsonEncode({
          ...settings,
          'today': today,
          'fixed_slots': [for (final s in fixedSlots) slotToJson(s)],
          'global_backgrounds': backgrounds,
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

    case 'suggest_user_setting':
      final key = _str(args['key']);
      final reason = _str(args['reason']);
      if (key == null || reason == null) {
        throw McpRpcError(errInvalidParams, 'suggest_user_setting 需要 key 与 reason');
      }
      const allowed = ['user_rules', 'weather_location', 'exceptions', 'identity_prompt', 'theme_mode'];
      if (!allowed.contains(key)) {
        throw McpRpcError(errInvalidParams, 'key 必须为仅用户自设键之一：$allowed');
      }
      final raw = await repo.settingsGet(SettingsKeys.aiSettingSuggestions);
      final list = raw == null
          ? <Map<String, Object?>>[]
          : (jsonDecode(raw) as List)
              .whereType<Map<Object?, Object?>>()
              .map((e) => Map<String, Object?>.from(e))
              .toList();
      list.add({
        'id': DateTime.now().microsecondsSinceEpoch.toString(),
        'key': key,
        'suggested_value': _str(args['suggested_value']),
        'reason': reason,
        'severity': _str(args['severity']) ?? 'normal',
        'created_at': DateTime.now().toIso8601String(),
        'status': 'pending',
      });
      await repo.settingsSet({SettingsKeys.aiSettingSuggestions: jsonEncode(list)});
      return [_text(jsonEncode({'ok': true, 'key': key}))];

    case 'upsert_background':
      final r = await _guarded(() => handler.execute(
            ScheduleCommand.fromJson({'op': 'upsert_background', ...args}),
            actor: CommandActor.ai,
          ));
      return [_text(jsonEncode(r.toJson()))];

    case 'merge_backgrounds':
      final r = await _guarded(() => handler.execute(
            ScheduleCommand.fromJson({'op': 'merge_backgrounds', ...args}),
            actor: CommandActor.ai,
          ));
      return [_text(jsonEncode(r.toJson()))];

    case 'upsert_facts':
      final r = await _guarded(() => handler.execute(
            ScheduleCommand.fromJson({'op': 'upsert_facts', ...args}),
            actor: CommandActor.ai,
          ));
      return [_text(jsonEncode(r.toJson()))];

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
