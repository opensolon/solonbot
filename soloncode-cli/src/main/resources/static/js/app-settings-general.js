/**
 * app-settings-general.js — 通用设置模块
 *
 * 依赖：layui.js（jQuery）
 */
(function () {
    'use strict';

    function showToast(msg, type) {
        if (typeof layer !== 'undefined' && layer.msg) {
            layer.msg(msg, { icon: type === 'error' ? 2 : 1, time: 2500, offset: '120px' });
        } else {
            alert(msg);
        }
    }

    // 解析带千位分隔符的数字（支持 _ 和 , 以及 k/m 后缀）
    function parseNumStr(s) {
        if (!s) return null;
        var raw = s.trim().replace(/[, _]/g, '');
        var matchK = raw.match(/^(\d+\.?\d*)k$/i);
        var matchM = raw.match(/^(\d+\.?\d*)m$/i);
        var n;
        if (matchK) {
            n = Math.round(parseFloat(matchK[1]) * 1000);
        } else if (matchM) {
            n = Math.round(parseFloat(matchM[1]) * 1000000);
        } else {
            n = parseInt(raw, 10);
        }
        return isNaN(n) ? null : n;
    }

    // 将数字格式化为千位分隔（用下划线，与 placeholder 一致），大于等于 1000 优先显示 xk 格式
    function formatNum(n) {
        if (n == null || n === '') return '';
        if (n >= 1000000 && n % 1000000 === 0) {
            return (n / 1000000) + 'm';
        } else if (n >= 1000) {
            return n % 1000 === 0 ? (n / 1000) + 'k' : (n / 1000).toFixed(1).replace(/\.0$/, '') + 'k';
        }
        return String(n).replace(/\B(?=(\d{3})+(?!\d))/g, '_');
    }

    /* ===== 字体设置 ===== */

    // 回填下拉：值不在预设项中时动态补一个自定义项（兼容手写配置）
    function setFontFamilySelect($sel, value) {
        if (!$sel.length) return;
        if (value && !$sel.find('option').filter(function () { return this.value === value; }).length) {
            $sel.append($('<option>').val(value).text(value));
        }
        $sel.val(value || '');
    }

    function currentFontScale() {
        var v = parseInt($('#generalUiFontScale').val(), 10);
        return isNaN(v) ? 1 : v / 100;
    }

    // 同步读数与滑块已选段填充比（--range-fill 供 settings.css 的轨道渐变使用）
    function updateFontScaleLabel() {
        var $range = $('#generalUiFontScale');
        var pct = Math.round(currentFontScale() * 100);
        $('#generalUiFontScaleValue').text(pct + '%');
        if (!$range.length) return;
        var min = parseInt($range.attr('min'), 10);
        var max = parseInt($range.attr('max'), 10);
        if (isNaN(min)) min = 85;
        if (isNaN(max)) max = 150;
        var ratio = max > min ? (pct - min) / (max - min) : 0;
        ratio = Math.max(0, Math.min(1, ratio));
        $range[0].style.setProperty('--range-fill', (ratio * 100).toFixed(2) + '%');
    }

    // 已持久化的字体基线（服务端值）。预览未保存时用它回滚。
    // app-ui.js 首屏校准也会写 window.savedFontBaseline，作为本面板 GET 未返回时的兜底。
    var savedFont = null;

    function fontBaseline() {
        return savedFont || window.savedFontBaseline || null;
    }

    function setFontBaseline(f) {
        savedFont = f;
        window.savedFontBaseline = f;
        window._fontPreviewDirty = false;
    }

    function readFontForm() {
        return {
            family: $('#generalUiFontFamily').val() || '',
            mono: $('#generalUiFontMono').val() || '',
            scale: currentFontScale()
        };
    }

    // 即时应用（仅运行时预览，持久化由保存按钮完成）
    // 置 dirty 标记：避免首屏那次异步校准把用户正在看的预览覆盖掉
    function previewFont() {
        if (typeof window.applyFont !== 'function') return;
        window._fontPreviewDirty = true;
        window.applyFont(readFontForm());
    }

    // 放弃未保存的预览，恢复到基线（关闭设置面板时调用）
    function revertFont() {
        var base = fontBaseline();
        if (!base || typeof window.applyFont !== 'function') return;
        window._fontPreviewDirty = false;
        window.applyFont(base);
        setFontFamilySelect($('#generalUiFontFamily'), base.family);
        setFontFamilySelect($('#generalUiFontMono'), base.mono);
        $('#generalUiFontScale').val(Math.round(base.scale * 100));
        updateFontScaleLabel();
    }

    $(document).on('change', '#generalUiFontFamily, #generalUiFontMono', previewFont);
    $(document).on('input change', '#generalUiFontScale', function () {
        updateFontScaleLabel();
        previewFont();
    });
    $(document).on('click', '#generalUiFontReset', function () {
        $('#generalUiFontFamily').val('');
        $('#generalUiFontMono').val('');
        $('#generalUiFontScale').val(100);
        updateFontScaleLabel();
        previewFont();
    });

    function loadGeneralSettings() {
        $.get('/web/settings/general', function (resp) {
            if (resp.code === 200 && resp.data) {
                var d = resp.data;
                $('#generalSessionWindowSize').val(d.sessionWindowSize != null ? formatNum(d.sessionWindowSize) : '');
                $('#generalCompressionMsgs').val((d.compressionThresholdMessages ?? d.summaryWindowSize) != null ? formatNum(d.compressionThresholdMessages ?? d.summaryWindowSize) : '');
                $('#generalCompressionPct').val(d.compressionThresholdPercent != null ? formatNum(d.compressionThresholdPercent) : '');
                $('#generalSandboxMode').prop('checked', !!d.sandboxMode);
                $('#generalSandboxAllowUserHome').prop('checked', d.sandboxAllowUserHome !== false);
                $('#generalSandboxSystemRestrict').prop('checked', !!d.sandboxSystemRestrict);
                $('#generalApiRetries').val(d.apiRetries != null ? formatNum(d.apiRetries) : '');
                $('#generalMcpRetries').val(d.mcpRetries != null ? formatNum(d.mcpRetries) : '');
                $('#generalModelRetries').val(d.modelRetries != null ? formatNum(d.modelRetries) : '');
                $('#generalMemoryEnabled').prop('checked', d.memoryEnabled !== false);
                $('#generalMemoryRelevanceCount').val(d.memoryRelevanceCount != null ? formatNum(d.memoryRelevanceCount) : '');
                $('#generalMemoryPriorityCount').val(d.memoryPriorityCount != null ? formatNum(d.memoryPriorityCount) : '');
                $('#generalMemorySummaryLength').val(d.memorySummaryLength != null ? formatNum(d.memorySummaryLength) : '');
                $('#generalMcpEnabled').prop('checked', d.mcpEnabled !== false);
                $('#generalOpenApiEnabled').prop('checked', d.openApiEnabled !== false);
                $('#generalBashAsyncEnabled').prop('checked', !!d.bashAsyncEnabled);
                $('#generalSubagentEnabled').prop('checked', d.subagentEnabled !== false);
                $('#generalLspEnabled').prop('checked', !!d.lspEnabled);
                $('#generalCliPrintSimplified').prop('checked', d.cliPrintSimplified !== false);
                window.cliPrintSimplified = d.cliPrintSimplified !== false;

                // Web 访问认证
                $('#generalWebAuthUser').val(d.webAuthUser || '');
                $('#generalWebAuthPass').val(d.webAuthPass || '');

                // 日志
                $('#generalLogLevel').val(d.logLevel || '');
                $('#generalLogFileMaxSize').val(d.logFileMaxSize || '');
                $('#generalLogMaxHistory').val(d.logMaxHistory != null ? d.logMaxHistory : '');

                // 字体：基线取服务端值（不经表单，避免与用户正在进行的预览互相污染）
                var serverFont = {
                    family: d.uiFontFamily || '',
                    mono: d.uiFontMono || '',
                    scale: d.uiFontScale != null ? d.uiFontScale : 1
                };
                var dirty = !!window._fontPreviewDirty;
                setFontBaseline(serverFont);
                // 用户已在预览：保留其表单与预览效果，只更新基线；否则按服务端值回填 + 校准
                // （localStorage 可能是上次未保存的预览残留）
                if (dirty) {
                    window._fontPreviewDirty = true;
                } else {
                    setFontFamilySelect($('#generalUiFontFamily'), serverFont.family);
                    setFontFamilySelect($('#generalUiFontMono'), serverFont.mono);
                    $('#generalUiFontScale').val(Math.round(serverFont.scale * 100));
                    updateFontScaleLabel();
                    if (typeof window.applyFont === 'function') window.applyFont(serverFont);
                }
            }
        }).fail(function () { console.error('[Settings] Failed to load general settings'); });

        // 加载 Loop Goal 配置
        $.get('/web/settings/loop', function (resp) {
            if (resp.code === 200 && resp.data) {
                var d = resp.data;
                $('#generalLoopDefaultMaxTokens').val(d.defaultMaxTokens != null && d.defaultMaxTokens > 0 ? formatNum(d.defaultMaxTokens) : '');
                $('#generalLoopDefaultMaxDuration').val(d.defaultMaxDurationMinutes != null && d.defaultMaxDurationMinutes > 0 ? d.defaultMaxDurationMinutes : '');
                $('#generalLoopStagnationThreshold').val(d.stagnationThreshold != null ? d.stagnationThreshold : '');
                $('#generalLoopMaxConsecutiveErrors').val(d.maxConsecutiveErrors != null ? d.maxConsecutiveErrors : '');
                $('#generalLoopBudgetWarningPercent').val(d.budgetWarningPercent != null ? d.budgetWarningPercent : '');
                $('#generalLoopBudgetCriticalPercent').val(d.budgetCriticalPercent != null ? d.budgetCriticalPercent : '');
                $('#generalLoopValidatorEnabled').prop('checked', d.validatorEnabled !== false);
            }
        }).fail(function () { console.error('[Settings] Failed to load loop settings'); });
    }

    // 皮肤切换由 app-settings-skin.js 统一处理（含服务端 activeSkin 持久化）

    $('#generalSaveBtn').on('click', function () {
        var $generalSaveBtn = $('#generalSaveBtn');
        var bodyObj = {
            sessionWindowSize: parseNumStr($('#generalSessionWindowSize').val().trim()),
            compressionThresholdMessages: parseNumStr($('#generalCompressionMsgs').val().trim()),
            compressionThresholdPercent: parseNumStr($('#generalCompressionPct').val().trim()),
            sandboxMode: $('#generalSandboxMode').is(':checked'),
            sandboxAllowUserHome: $('#generalSandboxAllowUserHome').is(':checked'),
            sandboxSystemRestrict: $('#generalSandboxSystemRestrict').is(':checked'),
            apiRetries: parseNumStr($('#generalApiRetries').val().trim()),
            mcpRetries: parseNumStr($('#generalMcpRetries').val().trim()),
            modelRetries: parseNumStr($('#generalModelRetries').val().trim()),
            memoryEnabled: $('#generalMemoryEnabled').is(':checked'),
            memoryRelevanceCount: parseNumStr($('#generalMemoryRelevanceCount').val().trim()),
            memoryPriorityCount: parseNumStr($('#generalMemoryPriorityCount').val().trim()),
            memorySummaryLength: parseNumStr($('#generalMemorySummaryLength').val().trim()),
            mcpEnabled: $('#generalMcpEnabled').is(':checked'),
            openApiEnabled: $('#generalOpenApiEnabled').is(':checked'),
            bashAsyncEnabled: $('#generalBashAsyncEnabled').is(':checked'),
            subagentEnabled: $('#generalSubagentEnabled').is(':checked'),
            lspEnabled: $('#generalLspEnabled').is(':checked'),
            cliPrintSimplified: $('#generalCliPrintSimplified').is(':checked'),
            webAuthUser: $('#generalWebAuthUser').val().trim() || null,
            webAuthPass: $('#generalWebAuthPass').val().trim() || null,
            logLevel: $('#generalLogLevel').val().trim() || null,
            logFileMaxSize: $('#generalLogFileMaxSize').val().trim() || null,
            logMaxHistory: parseNumStr($('#generalLogMaxHistory').val().trim()),
            uiFontFamily: $('#generalUiFontFamily').val() || null,
            uiFontMono: $('#generalUiFontMono').val() || null,
            uiFontScale: currentFontScale()
        };

        $generalSaveBtn.prop('disabled', true);

        // 收集所有保存请求的 promise
        var promises = [];

        // 1. 保存通用设置（用 .then() 过滤 resp.code）
        promises.push(
            $.ajax({ url: '/web/settings/general/save', method: 'POST', data: JSON.stringify(bodyObj), contentType: 'application/json', dataType: 'json' })
                .then(function (resp) {
                    if (resp.code !== 200) {
                        return $.Deferred().reject(resp.message || I18n.t('toast.unknownError')).promise();
                    }
                    window.cliPrintSimplified = bodyObj.cliPrintSimplified;
                    setFontBaseline(readFontForm());
                    return resp;
                })
        );

        // 2. 同步保存 Loop Goal 配置
        var loopObj = {
            defaultMaxTokens: parseNumStr($('#generalLoopDefaultMaxTokens').val().trim()) || 0,
            defaultMaxDurationMinutes: parseNumStr($('#generalLoopDefaultMaxDuration').val().trim()) || 0,
            stagnationThreshold: parseNumStr($('#generalLoopStagnationThreshold').val().trim()),
            maxConsecutiveErrors: parseNumStr($('#generalLoopMaxConsecutiveErrors').val().trim()),
            budgetWarningPercent: parseNumStr($('#generalLoopBudgetWarningPercent').val().trim()),
            budgetCriticalPercent: parseNumStr($('#generalLoopBudgetCriticalPercent').val().trim()),
            validatorEnabled: $('#generalLoopValidatorEnabled').is(':checked')
        };
        promises.push(
            $.ajax({ url: '/web/settings/loop/save', method: 'POST', data: JSON.stringify(loopObj), contentType: 'application/json', dataType: 'json' })
                .then(function (resp) {
                    if (resp.code !== 200) {
                        return $.Deferred().reject(resp.message || I18n.t('toast.unknownError')).promise();
                    }
                    return resp;
                })
        );

        // 3. 同步保存工具权限
        if (window._settingsPermission && typeof window._settingsPermission.save === 'function') {
            var p = window._settingsPermission.save();
            if (p) promises.push(p);
        }

        // 统一处理所有请求的结果
        $.when.apply($, promises)
            .done(function () { showToast(window.I18n ? window.I18n.t('toast.saveSuccess') : '\u4fdd\u5b58\u6210\u529f'); })
            .fail(function () { showToast(window.I18n ? window.I18n.t('toast.saveFailed') : '\u4fdd\u5b58\u5931\u8d25', 'error'); })
            .always(function () { $generalSaveBtn.prop('disabled', false); });
    });

    window._settingsGeneral = {
        load: loadGeneralSettings,
        revertFont: revertFont
    };
})();
