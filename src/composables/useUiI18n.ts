import { computed, ref } from 'vue'

export type LocalePreference = 'system' | 'zh-CN' | 'en'
type UiLocale = 'zh-CN' | 'en'

const STORAGE_KEY = 'anyclaw.ui.localePref'

const messages: Record<UiLocale, Record<string, string>> = {
  'zh-CN': {
    app_language: '界面语言',
    app_language_system: '跟随系统',
    app_language_zh_cn: '简体中文',
    app_language_en: 'English',
    sidebar_expand: '展开侧边栏',
    sidebar_collapse: '收起侧边栏',
    sidebar_search_threads: '搜索会话',
    sidebar_filter_threads: '筛选会话…',
    sidebar_clear_search: '清空搜索',
    sidebar_start_new_thread: '新建会话',
    openclaw_dashboard_label: '大龙虾仪表盘',
    openclaw_chat_title: '大龙虾聊天',
    openclaw_original_dashboard_label: '打开原版控制台',
    openclaw_back_codex: '返回 Codex',
    openclaw_new_session: '新建独立会话',
    openclaw_reset_session: '重置当前会话',
    openclaw_refresh: '刷新',
    openclaw_search_sessions: '搜索大龙虾会话',
    openclaw_filter_sessions: '筛选大龙虾会话…',
    openclaw_session_loading: '正在加载大龙虾会话…',
    openclaw_session_empty: '暂无大龙虾会话',
    openclaw_no_session_selected: '请先选择一个大龙虾会话',
    openclaw_process_on: '过程显示：开',
    openclaw_process_off: '过程显示：关',
    openclaw_load_older: '加载更早历史',
    openclaw_reset_lite: '恢复轻量',
    openclaw_history_window: '历史窗口 {count}',
    openclaw_send_placeholder: '输入消息发送给大龙虾…',
    openclaw_send_button: '发送',
    openclaw_cancel_button: '取消',
    openclaw_attach_button: '添加附件',
    openclaw_attach_camera: '拍照',
    openclaw_attach_gallery: '从相册选择',
    openclaw_attach_files: '从文件夹选择',
    openclaw_attach_remove: '移除',
    openclaw_attach_image_tag: '图片',
    openclaw_attach_file_tag: '文件',
    openclaw_health_ok: '网关状态：已连接',
    openclaw_health_fail: '网关状态：未连接',
    openclaw_create_session_failed: '创建会话失败',
    openclaw_reset_session_failed: '重置会话失败',
    openclaw_rename_session: '重命名会话',
    openclaw_rename_session_failed: '重命名会话失败',
    content_new_thread: '新建会话',
    content_choose_thread: '选择一个会话',
    home_hero: '开始对话',
    home_choose_folder: '选择工作目录',
    home_quick_guide: '先选目录再发消息，复杂配置优先交给智能体在对话中自动完成。',
    auto_refresh_in: '自动刷新 {seconds}s',
    auto_refresh_enable: '开启 4 秒自动刷新',
    delete_turn_confirm: '此操作会删除该消息所在整轮内容及其后续上下文，是否继续？',
    delete_message_failed: '删除消息失败',
    branch_message_failed: '创建分支失败',
    composer_model: '模型',
    composer_thinking: '思考强度',
    composer_send_message: '发送消息',
    composer_stop: '停止生成',
    composer_type_message: '输入消息…',
    composer_select_thread: '请先选择会话再发送消息',
    thinking_none: '关闭',
    thinking_minimal: '最小',
    thinking_low: '低',
    thinking_medium: '中',
    thinking_high: '高',
    thinking_xhigh: '超高',
    conversation_loading: '正在加载消息…',
    conversation_empty: '当前会话还没有消息。',
    request_accept: '同意',
    request_accept_session: '本会话同意',
    request_decline: '拒绝',
    request_cancel: '取消',
    request_submit_answers: '提交答案',
    request_other_answer: '其他答案',
    request_fail_tool_call: '工具调用失败',
    request_success_empty: '成功（空结果）',
    request_return_empty: '返回空结果',
    request_reject_unknown: '拒绝请求',
    message_action_copy: '复制',
    message_action_delete: '删除',
    message_action_branch: '分支',
    message_action_close: '关闭',
    image_preview_close: '关闭图片预览',
    menu_thread_display_settings: '会话显示设置',
    menu_show_all: '全部显示',
    menu_hide_all: '全部隐藏',
    menu_no_types: '暂无类型',
    threads_header: '会话',
    threads_no_match: '没有匹配的会话',
    threads_loading: '正在加载会话…',
    threads_no_items: '暂无会话',
    threads_show_more: '显示更多',
    threads_show_less: '收起',
    threads_confirm: '确认',
    project_edit_name: '编辑名称',
    project_remove: '移除',
    project_name: '项目名称',
    time_now: '刚刚',
    time_na: '未知',
    pin: '置顶',
    archive_thread: '归档会话',
  },
  en: {
    app_language: 'Language',
    app_language_system: 'Follow system',
    app_language_zh_cn: 'Simplified Chinese',
    app_language_en: 'English',
    sidebar_expand: 'Expand sidebar',
    sidebar_collapse: 'Collapse sidebar',
    sidebar_search_threads: 'Search threads',
    sidebar_filter_threads: 'Filter threads...',
    sidebar_clear_search: 'Clear search',
    sidebar_start_new_thread: 'Start new thread',
    openclaw_dashboard_label: 'OpenClaw Dashboard',
    openclaw_chat_title: 'OpenClaw Chat',
    openclaw_original_dashboard_label: 'Open Original Dashboard',
    openclaw_back_codex: 'Back to Codex',
    openclaw_new_session: 'New Independent Session',
    openclaw_reset_session: 'Reset Current Session',
    openclaw_refresh: 'Refresh',
    openclaw_search_sessions: 'Search OpenClaw sessions',
    openclaw_filter_sessions: 'Filter OpenClaw sessions...',
    openclaw_session_loading: 'Loading OpenClaw sessions...',
    openclaw_session_empty: 'No OpenClaw sessions yet',
    openclaw_no_session_selected: 'Select an OpenClaw session first',
    openclaw_process_on: 'Process view: on',
    openclaw_process_off: 'Process view: off',
    openclaw_load_older: 'Load older history',
    openclaw_reset_lite: 'Reset lite',
    openclaw_history_window: 'History window {count}',
    openclaw_send_placeholder: 'Type a message to OpenClaw...',
    openclaw_send_button: 'Send',
    openclaw_cancel_button: 'Cancel',
    openclaw_attach_button: 'Attach',
    openclaw_attach_camera: 'Take photo',
    openclaw_attach_gallery: 'Choose from gallery',
    openclaw_attach_files: 'Choose from files',
    openclaw_attach_remove: 'Remove',
    openclaw_attach_image_tag: 'Image',
    openclaw_attach_file_tag: 'File',
    openclaw_health_ok: 'Gateway: connected',
    openclaw_health_fail: 'Gateway: disconnected',
    openclaw_create_session_failed: 'Failed to create session',
    openclaw_reset_session_failed: 'Failed to reset session',
    openclaw_rename_session: 'Rename session',
    openclaw_rename_session_failed: 'Failed to rename session',
    content_new_thread: 'New thread',
    content_choose_thread: 'Choose a thread',
    home_hero: "Let's build",
    home_choose_folder: 'Choose folder',
    home_quick_guide: 'Choose a folder and send messages. Let the agent handle advanced setup in chat.',
    auto_refresh_in: 'Auto refresh in {seconds}s',
    auto_refresh_enable: 'Enable 4s refresh',
    delete_turn_confirm: 'This deletes the full turn from this point onward. Continue?',
    delete_message_failed: 'Failed to delete message',
    branch_message_failed: 'Failed to create branch',
    composer_model: 'Model',
    composer_thinking: 'Thinking',
    composer_send_message: 'Send message',
    composer_stop: 'Stop',
    composer_type_message: 'Type a message...',
    composer_select_thread: 'Select a thread to send a message',
    thinking_none: 'None',
    thinking_minimal: 'Minimal',
    thinking_low: 'Low',
    thinking_medium: 'Medium',
    thinking_high: 'High',
    thinking_xhigh: 'Extra high',
    conversation_loading: 'Loading messages...',
    conversation_empty: 'No messages in this thread yet.',
    request_accept: 'Accept',
    request_accept_session: 'Accept for Session',
    request_decline: 'Decline',
    request_cancel: 'Cancel',
    request_submit_answers: 'Submit Answers',
    request_other_answer: 'Other answer',
    request_fail_tool_call: 'Fail Tool Call',
    request_success_empty: 'Success (Empty)',
    request_return_empty: 'Return Empty Result',
    request_reject_unknown: 'Reject Request',
    message_action_copy: 'Copy',
    message_action_delete: 'Delete',
    message_action_branch: 'Branch',
    message_action_close: 'Close',
    image_preview_close: 'Close image preview',
    menu_thread_display_settings: 'Thread display settings',
    menu_show_all: 'Show all',
    menu_hide_all: 'Hide all',
    menu_no_types: 'No types yet',
    threads_header: 'Threads',
    threads_no_match: 'No matching threads',
    threads_loading: 'Loading threads...',
    threads_no_items: 'No threads',
    threads_show_more: 'Show more',
    threads_show_less: 'Show less',
    threads_confirm: 'Confirm',
    project_edit_name: 'Edit name',
    project_remove: 'Remove',
    project_name: 'Project name',
    time_now: 'now',
    time_na: 'n/a',
    pin: 'Pin',
    archive_thread: 'Archive thread',
  },
}

const localePreference = ref<LocalePreference>(loadLocalePreference())

function loadLocalePreference(): LocalePreference {
  if (typeof window === 'undefined') return 'system'
  const raw = window.localStorage.getItem(STORAGE_KEY)
  if (raw === 'zh-CN' || raw === 'en' || raw === 'system') return raw
  return 'system'
}

function saveLocalePreference(value: LocalePreference): void {
  if (typeof window === 'undefined') return
  window.localStorage.setItem(STORAGE_KEY, value)
}

function resolveSystemLocale(): UiLocale {
  if (typeof navigator === 'undefined') return 'en'
  const lang = (navigator.language || '').toLowerCase()
  if (lang.startsWith('zh')) return 'zh-CN'
  return 'en'
}

const resolvedLocale = computed<UiLocale>(() => {
  if (localePreference.value === 'system') {
    return resolveSystemLocale()
  }
  return localePreference.value
})

function formatMessage(template: string, params?: Record<string, string | number>): string {
  if (!params) return template
  let text = template
  for (const [key, value] of Object.entries(params)) {
    text = text.split(`{${key}}`).join(String(value))
  }
  return text
}

export function useUiI18n() {
  function setLocalePreference(value: LocalePreference): void {
    localePreference.value = value
    saveLocalePreference(value)
  }

  function t(key: string, params?: Record<string, string | number>): string {
    const locale = resolvedLocale.value
    const table = messages[locale]
    const fallback = messages.en
    const template = table[key] ?? fallback[key] ?? key
    return formatMessage(template, params)
  }

  return {
    localePreference,
    resolvedLocale,
    setLocalePreference,
    t,
  }
}
