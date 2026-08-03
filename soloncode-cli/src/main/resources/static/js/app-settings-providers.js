/**
 * 供应商设置管理模块
 *
 * 负责供应商的增删改查、模型列表拉取等功能
 */
;(function () {
    'use strict';

    var core = window._settingsCore;
    var postJson = core.postJson;

    // ==================== 状态管理 ====================
    var providers = [];
    var currentProvider = null; // 当前编辑的供应商（null 表示新增）
    var fetchedModels = []; // 已拉取的模型列表

    // ==================== DOM 元素 ====================
    var $listView = $('#providersListView');
    var $formView = $('#providersFormView');
    var $providerList = $('#providerList');
    var $formTitle = $('#providerFormTitle');
    var $formActions = $('#providerFormActions');
    var $modelsList = $('#providerModelsList');
    var $modelsEmpty = $('#providerModelsEmpty');

    // ==================== 初始化 ====================
    function init() {
        bindEvents();
        loadProvidersList();
    }

    function bindEvents() {
        // 添加供应商按钮
        $('#providerAddBtn').on('click', function () {
            showForm(null);
        });

        // 返回按钮
        $('#providerBackBtn').on('click', function () {
            showList();
        });

        // 拉取模型列表
        $('#providerFetchModelsBtn').on('click', function () {
            fetchModels();
        });

        // 清空模型列表
        $('#providerClearModelsBtn').on('click', function () {
            if (fetchedModels.length === 0) return;
            layui.layer.confirm(I18n.t('provider.clearConfirm'), {
                btn: [I18n.t('provider.clearBtn'), I18n.t('common.cancel')],
                icon: 3
            }, function (index) {
                layui.layer.close(index);
                fetchedModels = [];
                renderModelsList();
            });
        });

        // 手动添加模型
        $('#providerAddModelBtn').on('click', function () {
            addManualModel();
        });

        // 模型列表 - 删除手动模型
        $modelsList.on('click', '.provider-model-remove-btn', function () {
            var modelId = $(this).closest('.provider-model-item').data('model-id');
            removeManualModel(modelId);
        });

        // 保存按钮
        $('#providerSaveBtn').on('click', function () {
            saveProvider();
        });

        // 删除按钮
        $('#providerFormDeleteBtn').on('click', function () {
            deleteProvider();
        });

        // 编辑按钮（供应商列表项）
        $providerList.on('click', '.provider-edit-btn', function (e) {
            e.stopPropagation();
            var name = $(this).closest('.settings-list-item').data('name');
            editProvider(name);
        });

        // 启用/禁用开关
        $providerList.on('change', '.provider-toggle', function () {
            var name = $(this).closest('.settings-list-item').data('name');
            var enabled = $(this).prop('checked');
            toggleProvider(name, enabled);
        });

        // 模型列表 - 启用/禁用开关
        $modelsList.on('change', '.provider-model-toggle', function () {
            var modelId = $(this).closest('.provider-model-item').data('model-id');
            var enabled = $(this).prop('checked');
            var llmName = $(this).data('llm-name');
            var isSynced = $(this).data('synced') === true || $(this).data('synced') === 'true';
            toggleProviderModel(modelId, enabled, llmName, isSynced);
        });

        // 批量选择菜单
        $('#providerModelsSelectToggle').on('click', function (e) {
            e.stopPropagation();
            $('#providerModelsActionMenu').toggleClass('show');
        });

        $(document).on('click', function (e) {
            if ($(e.target).closest('.provider-model-menu-wrap').length === 0) {
                $('#providerModelsActionMenu').removeClass('show');
            }
        });

        $('#providerModelsSelectAll, #providerModelsSelectNone, #providerModelsInvert').on('click', function () {
            var action = this.id;
            var changed = false;

            $modelsList.find('.provider-model-toggle').each(function () {
                var $toggle = $(this);
                var nextChecked = $toggle.prop('checked');

                if (action === 'providerModelsSelectAll') {
                    nextChecked = true;
                } else if (action === 'providerModelsSelectNone') {
                    nextChecked = false;
                } else if (action === 'providerModelsInvert') {
                    nextChecked = !$toggle.prop('checked');
                }

                if ($toggle.prop('checked') !== nextChecked) {
                    changed = true;
                    $toggle.prop('checked', nextChecked).trigger('change');
                }
            });

            $('#providerModelsActionMenu').removeClass('show');
        });

        // 作用域切换
        $('.settings-scope-toggle').on('click', '.settings-scope-btn', function () {
            var $toggle = $(this).closest('.settings-scope-toggle');
            var target = $toggle.data('target');
            var scope = $(this).data('scope');
            $toggle.find('.settings-scope-btn').removeClass('active');
            $(this).addClass('active');
            $('#' + target).val(scope);
        });
    }

    // ==================== 列表视图 ====================

    function getStandardAbbr(standard) {
        if (!standard) return '';
        return standard.split('-').map(function(p) { return p.charAt(0).toUpperCase(); }).join('');
    }

    function loadProvidersList() {
        $.ajax({
            url: '/web/settings/llm/providers',
            method: 'GET',
            success: function (res) {
                if (res.code === 200) {
                    providers = res.data || [];
                    renderProvidersList();
                }
            },
            error: function () {
                layui.layer.msg(I18n.t('provider.loadFailed'), { icon: 2 });
            }
        });
    }

    function renderProvidersList() {
        var html = '';
        if (providers.length === 0) {
            html = '<div class="mcp-empty-state"><div class="mcp-empty-icon"><svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="var(--text-secondary)" stroke-width="1.5"><circle cx="12" cy="12" r="3"/><path d="M12 1v6M12 17v6M4.22 4.22l4.24 4.24M15.54 15.54l4.24 4.24M1 12h6M17 12h6M4.22 19.78l4.24-4.24M15.54 8.46l4.24-4.24"/></svg></div><div class="mcp-empty-title">' + I18n.t('provider.empty') + '</div><div class="mcp-empty-desc">' + I18n.t('provider.emptyDesc') + '</div></div>';
        } else {
            providers.forEach(function (provider) {
                html += renderProviderItem(provider);
            });
        }
        $providerList.html(html);
    }

    function renderProviderItem(provider) {
        var modelsCount = (provider.models || []).length;

        return '<div class="settings-list-item' + (provider.enabled === false ? ' disabled' : '') + '" data-name="' + provider.name + '">' +
            '<div class="settings-list-icon">' + (getStandardAbbr(provider.standard) || 'F') + '</div>' +
            '<div class="settings-list-info">' +
                '<div class="settings-list-title">' + provider.name + ' <span class="settings-inline-tag">[' + (provider.standard || 'openai') + ']</span></div>' +
                '<div class="settings-list-desc">' + (provider.apiUrl || I18n.t('provider.notConfigured')) + ' - ' + I18n.t('provider.modelCount', {n: modelsCount}) + '</div>' +
            '</div>' +
            '<div class="settings-list-actions">' +
                '<button class="settings-action-btn edit provider-edit-btn" title="' + I18n.t('provider.edit') + '"><svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/><path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/></svg></button>' +
                '<label class="toggle-switch" title="' + (provider.enabled ? I18n.t('provider.toggle.disable') : I18n.t('provider.toggle.enable')) + '">' +
                    '<input type="checkbox" ' + (provider.enabled ? 'checked' : '') + ' data-name="' + provider.name + '" class="provider-toggle"/>' +
                    '<span class="toggle-slider"></span>' +
                '</label>' +
            '</div>' +
        '</div>';
    }

    // ==================== 表单视图 ====================
    function showForm(provider) {
        currentProvider = provider;
        fetchedModels = (provider && provider.models) ? provider.models.slice() : [];
        // 兼容旧数据：剥离模型中可能残留的 [1m]/[256k] 后缀，并同步上下文长度
        fetchedModels.forEach(function (m) {
            if (m && m.id) {
                var parsed = parseModelContextSuffix(m.id);
                if (parsed.id !== m.id) {
                    m.id = parsed.id;
                    if (parsed.contextLength > 0 && !m.maxInputTokens) {
                        m.maxInputTokens = parsed.contextLength;
                    }
                }
            }
        });

        // 切换视图
        $listView.hide();
        $formView.show();

        // 设置标题
        $formTitle.text(I18n.t(provider ? 'provider.editTitle' : 'provider.addTitle'));
        $formActions.toggle(!!provider);

        // 填充表单
        $('#providerName').val(provider ? provider.name : '').prop('readonly', !!provider);
        $('#providerStandard').val(provider ? provider.standard : 'openai');
        $('#providerApiUrl').val(provider ? provider.apiUrl : '');
        $('#providerApiKey').val(provider ? provider.apiKey : '');
        $('#providerScope').val(provider ? (provider.scope || 'user') : 'user');

        // 设置作用域按钮状态
        var scope = provider ? (provider.scope || 'user') : 'user';
        $('.settings-scope-toggle[data-target="providerScope"] .settings-scope-btn').removeClass('active');
        $('.settings-scope-toggle[data-target="providerScope"] .settings-scope-btn[data-scope="' + scope + '"]').addClass('active');

        // 加载 LLM 模型缓存后渲染模型列表
        loadLlmModelsCache(function () {
            renderModelsList();
        });
    }

    function showList() {
        $formView.hide();
        $listView.addClass('slide-back').show();
        setTimeout(function(){ $listView.removeClass('slide-back'); }, 260);
        currentProvider = null;
        fetchedModels = [];
        loadProvidersList();
    }

    // ==================== 模型列表 ====================
    var llmModelsCache = {}; // 缓存 LLM 模型列表，用于判断是否已同步

    /**
     * 解析模型名中的上下文长度后缀（Claude Code 风格），如 glm5.2[1m] -> {id:'glm5.2', contextLength:1000000}
     * 支持 k（千）和 m（百万）单位；无有效后缀时 contextLength 为 0
     */
    function parseModelContextSuffix(modelName) {
        var match = String(modelName || '').trim().match(/^(.*)\[(\d+(?:\.\d+)?)([km])\]$/i);
        if (!match) {
            return { id: String(modelName || '').trim(), contextLength: 0 };
        }
        var value = parseFloat(match[2]);
        var unit = match[3].toLowerCase();
        var contextLength = unit === 'm' ? Math.round(value * 1000000) : Math.round(value * 1000);
        return { id: match[1], contextLength: contextLength };
    }

    function addManualModel() {
        var dialogHtml = '<div class="model-add-overlay" id="modelAddOverlay">'
            + '<div class="model-add-dialog">'
            + '<div class="model-add-header">'
            + '<span class="model-add-title">' + I18n.t('provider.models.addTitle') + '</span>'
            + '<button class="model-add-close" id="modelAddClose">&times;</button>'
            + '</div>'
            + '<div class="model-add-body">'
            + '<div class="form-group">'
            + '<label>' + I18n.t('provider.modelName') + ' <span class="required">*</span></label>'
            + '<input type="text" id="manualModelName" placeholder="' + I18n.t('provider.modelNamePlaceholder') + '">'
            + '</div>'
            + '<div class="form-group">'
            + '<label>' + I18n.t('provider.contextLength') + '</label>'
            + '<input type="text" id="manualModelTokens" inputmode="numeric" placeholder="' + I18n.t('provider.contextLengthPlaceholder') + '" list="manualContextLengthList" autocomplete="off">'
            + '<datalist id="manualContextLengthList">'
            + '<option value="128k">'
            + '<option value="256k">'
            + '<option value="512k">'
+ '<option value="1m">'
            + '</datalist>'
            + '</div>'
            + '</div>'
            + '<div class="model-add-footer">'
            + '<button class="btn-secondary" id="modelAddCancel">' + I18n.t('common.cancel') + '</button>'
            + '<button class="btn-primary" id="modelAddConfirm">' + I18n.t('provider.confirmAdd') + '</button>'
            + '</div>'
            + '</div>'
            + '</div>';

        $('body').append(dialogHtml);

        var $overlay = $('#modelAddOverlay');

        function doAdd() {
            var modelInput = $overlay.find('#manualModelName').val().trim();
            var maxTokens = $overlay.find('#manualModelTokens').val().trim();

            // 解析模型名中的 [1m]/[256k] 后缀（Claude Code 风格）
            var parsed = parseModelContextSuffix(modelInput);
            var modelId = parsed.id;
            var suffixLength = parsed.contextLength;

            if (!modelId) {
                layui.layer.msg(I18n.t('provider.modelNameRequired'), { icon: 0 });
                return;
            }

            var exists = fetchedModels.some(function (m) {
                return m.id === modelId;
            });
            if (exists) {
                layui.layer.msg(I18n.t('provider.modelExists', {name: modelId}), { icon: 0 });
                return;
            }

            var newModel = { id: modelId, manual: true };
            // 模型名带后缀时优先使用后缀解析值；否则解析用户填写的上下文长度
            var effectiveTokens = suffixLength > 0 ? String(suffixLength) : maxTokens;
            if (effectiveTokens) {
                var trimmed = effectiveTokens.replace(/[, _]/g, '');
                var matchK = trimmed.match(/^(\d+\.?\d*)k$/i);
                var matchM = trimmed.match(/^(\d+\.?\d*)m$/i);
                if (matchK) {
                    newModel.maxInputTokens = Math.round(parseFloat(matchK[1]) * 1000);
                } else if (matchM) {
                    newModel.maxInputTokens = Math.round(parseFloat(matchM[1]) * 1000000);
                } else if (parseInt(trimmed) > 0) {
                    newModel.maxInputTokens = parseInt(trimmed);
                }
            }
            fetchedModels.push(newModel);
            renderModelsList();
            $overlay.remove();
        }

        $('#modelAddConfirm').on('click', doAdd);
        $('#modelAddCancel, #modelAddClose').on('click', function() {
            $overlay.remove();
        });
        $overlay.on('click', function(e) {
            if (e.target === this) $overlay.remove();
        });
        $overlay.on('keydown', 'input', function(e) {
            if (e.key === 'Enter' && !isInputComposing(e)) {
                e.preventDefault();
                doAdd();
            }
        });
        setTimeout(function() {
            $overlay.find('#manualModelName').focus();
        }, 100);
    }

    function removeManualModel(modelId) {
        fetchedModels = fetchedModels.filter(function (m) {
            return m.id !== modelId;
        });
        renderModelsList();
    }

    function fetchModels() {
        var apiUrl = $('#providerApiUrl').val();
        var apiKey = $('#providerApiKey').val();
        var standard = $('#providerStandard').val();

        if (!apiUrl) {
            layui.layer.msg(I18n.t('provider.apiUrlRequired'), { icon: 0 });
            return;
        }

        var $btn = $('#providerFetchModelsBtn');
        $btn.prop('disabled', true).html('<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="animation:spin 1s linear infinite"><path d="M21 12a9 9 0 1 1-6.219-8.56"/></svg>');

        $.ajax({
            url: '/web/settings/llm/providers/fetch',
            method: 'POST',
            data: {
                apiUrl: apiUrl,
                apiKey: apiKey,
                standard: standard
            },
            success: function (res) {
                $btn.prop('disabled', false).html('<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="23 4 23 10 17 10"/><path d="M20.49 15a9 9 0 1 1-2.12-9.36L23 10"/></svg>');
                if (res.code === 200) {
                    try {
                        var data = typeof res.data === 'string' ? JSON.parse(res.data) : res.data;
                        var models = data.data || data.models || data || [];
                        // 保留手动添加的模型，合并拉取的模型
                        var manualModels = fetchedModels.filter(function (m) {
                            return m.manual === true;
                        });
                        var fetchedMapped = models.map(function (m) {
                            return {
                                id: m.id || m.name || m,
                                displayName: m.displayName || m.display_name || '',
                                ownedBy: m.ownedBy || m.owned_by || '',
                                type: m.type || '',
                                maxInputTokens: m.maxInputTokens || m.max_input_tokens || m.contextLength || m.context_length || 0,
                                maxTokens: m.maxTokens || m.max_tokens || 0,
                                manual: false
                            };
                        });
                        // 手动模型去重：如果手动模型 id 已在拉取列表中，保留手动标记
                        var fetchedIds = {};
                        fetchedMapped.forEach(function (m) { fetchedIds[m.id] = m; });
                        manualModels.forEach(function (mm) {
                            if (fetchedIds[mm.id]) {
                                fetchedIds[mm.id].manual = true;
                                if (mm.displayName) {
                                    fetchedIds[mm.id].displayName = mm.displayName;
                                }
                                if (mm.ownedBy) {
                                    fetchedIds[mm.id].ownedBy = mm.ownedBy;
                                }
                                if (mm.maxInputTokens) {
                                    fetchedIds[mm.id].maxInputTokens = mm.maxInputTokens;
                                }
                                if (mm.maxTokens) {
                                    fetchedIds[mm.id].maxTokens = mm.maxTokens;
                                }
                            } else {
                                fetchedMapped.push(mm);
                            }
                        });
                        fetchedModels = fetchedMapped;
                        // 加载 LLM 模型列表缓存，用于判断同步状态
                        loadLlmModelsCache(function () {
                            renderModelsList();
                        });
                        layui.layer.msg(I18n.t('provider.fetchOk', {n: fetchedModels.length}), { icon: 1 });
                    } catch (e) {
                        layui.layer.msg(I18n.t('provider.fetchParseFailed'), { icon: 2 });
                    }
                } else {
                    layui.layer.msg(res.msg || I18n.t('provider.fetchFailed'), { icon: 2 });
                }
            },
            error: function (xhr) {
                $btn.prop('disabled', false).html('<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="23 4 23 10 17 10"/><path d="M20.49 15a9 9 0 1 1-2.12-9.36L23 10"/></svg>');
                layui.layer.msg(I18n.t('provider.fetchListFailed') + ': ' + (xhr.responseText || I18n.t('toast.networkError')), { icon: 2 });
            }
        });
    }

    // 加载 LLM 模型列表缓存
    function loadLlmModelsCache(callback) {
        $.get('/web/settings/llm/models', function (res) {
            if (res.code === 200 && res.data) {
                var list = res.data.list || (Array.isArray(res.data) ? res.data : []);
                llmModelsCache = {};
                list.forEach(function (item) {
                    if (item.name) {
                        llmModelsCache[item.name] = item;
                    }
                });
            }
            if (callback) callback();
        }).fail(function () {
            if (callback) callback();
        });
    }

    function renderModelsList() {
        if (fetchedModels.length === 0) {
            $modelsEmpty.show();
            $modelsList.hide();
            return;
        }

        $modelsEmpty.hide();
        $modelsList.show();

        var providerName = $('#providerName').val() || '';
        var providerStandard = $('#providerStandard').val();
        var providerStandardAbbr = getStandardAbbr(providerStandard) || 'F';
        var providerEnabled = currentProvider ? currentProvider.enabled !== false : true;
        var html = '';
        fetchedModels.forEach(function (model) {
            // 检查是否已同步到 LLM
            var llmName = providerName ? providerName + '-' + model.id : model.id;
            var syncedModel = llmModelsCache[llmName];
            var isSynced = !!syncedModel;
            // 使用 LLM 缓存的启用状态，如果未同步则使用供应商的启用状态
            var enabled = isSynced ? (syncedModel.enabled !== false && syncedModel.visibled !== false) : providerEnabled;

            var manualTag = model.manual ? ' <span class="provider-model-manual-tag">' + I18n.t('provider.manualTag') + '</span>' : '';
            var removeBtn = model.manual
                ? '<button class="provider-model-remove-btn" title="' + I18n.t('provider.remove') + '"><svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg></button>'
                : '';
            // 上下文长度标识（如 1m / 256k）
            var contextTag = '';
            if (model.maxInputTokens && model.maxInputTokens > 0) {
                var cl = model.maxInputTokens;
                var clStr;
                if (cl >= 1000000 && cl % 1000000 === 0) {
                    clStr = (cl / 1000000) + 'm';
                } else if (cl >= 1000) {
                    clStr = (cl % 1000 === 0 ? (cl / 1000) + 'k' : (cl / 1000).toFixed(1).replace(/\.0$/, '') + 'k');
                } else {
                    clStr = String(cl);
                }
                contextTag = ' <span class="provider-model-context">' + clStr + '</span>';
            }
            html += '<div class="provider-model-item' + (!enabled ? ' disabled' : '') + '" data-model-id="' + model.id + '">' +
                '<div class="provider-model-icon">' + providerStandardAbbr + '</div>' +
                '<div class="provider-model-info">' +
                    '<div class="provider-model-name">' + model.id + contextTag + manualTag + (isSynced ? ' <span class="provider-model-synced">' + I18n.t('provider.syncedBadge') + '</span>' : '') + '</div>' +
                '</div>' +
                '<div class="provider-model-actions">' +
                    removeBtn +
                    '<label class="toggle-switch" title="' + (enabled ? I18n.t('provider.toggle.disable') : I18n.t('provider.toggle.enable')) + '">' +
                        '<input type="checkbox" ' + (enabled ? 'checked' : '') + ' class="provider-model-toggle" data-synced="' + isSynced + '" data-llm-name="' + llmName + '"/>' +
                        '<span class="toggle-slider"></span>' +
                    '</label>' +
                '</div>' +
            '</div>';
        });
        $modelsList.html(html);
    }

    function toggleProviderModel(modelId, enabled, llmName, isSynced) {
        // 如果已同步到 LLM，直接调用 LLM 接口更新
        if (isSynced && llmName) {
            postJson('/web/settings/llm/models/toggle', { name: llmName, enabled: enabled }, function (resp) {
                if (resp.code === 200) {
                    // 更新缓存
                    if (llmModelsCache[llmName]) {
                        llmModelsCache[llmName].enabled = enabled;
                    }
                    // 刷新 LLM 模型列表
                    if (window._settingsLlm) {
                        window._settingsLlm.load();
                    }
                } else {
                    layui.layer.msg(I18n.t('toast.operateFailed') + ': ' + (resp.message || I18n.t('toast.unknownError')), { icon: 2 });
                    // 回滚状态
                    renderModelsList();
                }
            });
        }
    }

    // ==================== CRUD 操作 ====================
    function editProvider(name) {
        $.ajax({
            url: '/web/settings/llm/providers/get',
            method: 'GET',
            data: { name: name },
            success: function (res) {
                if (res.code === 200) {
                    showForm(res.data);
                } else {
                    layui.layer.msg(res.msg || I18n.t('provider.loadDetailFailed'), { icon: 2 });
                }
            },
            error: function () {
                layui.layer.msg(I18n.t('provider.loadDetailFailed'), { icon: 2 });
            }
        });
    }

    function saveProvider() {
        var name = $('#providerName').val();
        var standard = $('#providerStandard').val();
        var apiUrl = $('#providerApiUrl').val();
        var apiKey = $('#providerApiKey').val();
        var scope = $('#providerScope').val();
        var models = fetchedModels.map(function (m) {
            var model = { id: m.id, manual: m.manual || false };
            if (m.displayName) {
                model.displayName = m.displayName;
            }
            if (m.ownedBy) {
                model.ownedBy = m.ownedBy;
            }
            if (m.type) {
                model.type = m.type;
            }
            if (m.maxInputTokens) {
                model.maxInputTokens = m.maxInputTokens;
            }
            if (m.maxTokens) {
                model.maxTokens = m.maxTokens;
            }
            return model;
        });

        if (!name) {
            layui.layer.msg(I18n.t('provider.nameRequired'), { icon: 0 });
            return;
        }
        if (!apiUrl) {
            layui.layer.msg(I18n.t('provider.apiUrlRequired'), { icon: 0 });
            return;
        }

        var data = {
            name: name,
            standard: standard,
            apiUrl: apiUrl,
            apiKey: apiKey,
            scope: scope,
            models: models,
            enabled: true
        };

        // 如果是编辑模式，添加 originalName
        if (currentProvider) {
            data.originalName = currentProvider.name;
        }

        var url = currentProvider ? '/web/settings/llm/providers/update' : '/web/settings/llm/providers/add';

        $.ajax({
            url: url,
            method: 'POST',
            contentType: 'application/json',
            data: JSON.stringify(data),
            success: function (res) {
                if (res.code === 200) {
                    layui.layer.msg(I18n.t(currentProvider ? 'provider.updated' : 'provider.added'), { icon: 1 });
                    // 同步模型到 LLM 模型列表
                    syncModelsToLlm(data);
                    showList();
                } else {
                    layui.layer.msg(res.msg || I18n.t('toast.saveFailed'), { icon: 2 });
                }
            },
            error: function () {
                layui.layer.msg(I18n.t('toast.saveFailed'), { icon: 2 });
            }
        });
    }

    function syncModelsToLlm(providerData) {
        // 调用后端接口同步模型
        $.ajax({
            url: '/web/settings/llm/providers/sync-models',
            method: 'POST',
            contentType: 'application/json',
            data: JSON.stringify({
                providerName: providerData.name,
                models: providerData.models || []
            }),
            success: function (res) {
                if (res.code === 200 && res.data > 0) {
                    layui.layer.msg(I18n.t('provider.syncedModels', {n: res.data}), { icon: 1 });
                    // 刷新 LLM 模型列表
                    if (window._settingsLlm) {
                        window._settingsLlm.load();
                    }
                    // 通知聊天组件刷新模型下拉列表
                    if (typeof window.reloadModels === 'function') {
                        window.reloadModels();
                    }
                }
            }
        });
    }

    function deleteProvider() {
        if (!currentProvider) return;

        layui.layer.confirm(I18n.t('provider.deleteConfirm', {name: currentProvider.name}), {
            btn: [I18n.t('common.delete'), I18n.t('common.cancel')],
            icon: 3
        }, function (index) {
            layui.layer.close(index);
            $.ajax({
                url: '/web/settings/llm/providers/remove',
                method: 'POST',
                data: { name: currentProvider.name },
                success: function (res) {
                    if (res.code === 200) {
                        layui.layer.msg(I18n.t('provider.deleted'), { icon: 1 });
                        showList();
                        // 刷新 LLM 模型列表（供应商删除时关联模型也会删除）
                        if (window._settingsLlm) {
                            window._settingsLlm.load();
                        }
                        // 通知聊天组件刷新模型下拉列表
                        if (typeof window.reloadModels === 'function') {
                            window.reloadModels();
                        }
                    } else {
                        layui.layer.msg(res.msg || I18n.t('provider.deleteFailed'), { icon: 2 });
                    }
                },
                error: function () {
                    layui.layer.msg(I18n.t('provider.deleteFailed'), { icon: 2 });
                }
            });
        });
    }

    function toggleProvider(name, enabled) {
        $.ajax({
            url: '/web/settings/llm/providers/toggle',
            method: 'POST',
            data: { name: name, enabled: enabled },
            success: function (res) {
                if (res.code === 200) {
                    layui.layer.msg(I18n.t(enabled ? 'provider.enableOk' : 'provider.disableOk'), { icon: 1 });
                    // 刷新供应商列表 UI（更新 disabled 样式）
                    loadProvidersList();
                    // 刷新 LLM 模型列表（供应商禁用时关联模型会禁用）
                    if (window._settingsLlm) {
                        window._settingsLlm.load();
                    }
                    // 通知聊天组件刷新模型下拉列表
                    if (typeof window.reloadModels === 'function') {
                        window.reloadModels();
                    }
                } else {
                    layui.layer.msg(res.msg || I18n.t('toast.operateFailed'), { icon: 2 });
                    loadProvidersList();
                }
            },
            error: function () {
                layui.layer.msg(I18n.t('toast.operateFailed'), { icon: 2 });
                loadProvidersList();
            }
        });
    }

    // ==================== 暴露全局接口 ====================
    window.settingsProviders = {
        init: init,
        loadList: loadProvidersList,
        showList: showList
    };

    // Provider API Key 显示切换
    $(document).on('click', '#providerApiKeyToggle', function () {
        var $input = $('#providerApiKey');
        if ($input.attr('type') === 'password') {
            $input.attr('type', 'text');
            $(this).html('<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19m-6.72-1.07a3 3 0 1 1-4.24-4.24"/><line x1="1" y1="1" x2="23" y2="23"/></svg>');
        } else {
            $input.attr('type', 'password');
            $(this).html('<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/><circle cx="12" cy="12" r="3"/></svg>');
        }
    });

    // 自动初始化
    $(document).ready(function () {
        init();
    });
})();
