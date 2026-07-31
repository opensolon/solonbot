/**
 * i18n.js — 轻量国际化核心模块
 * 支持：文本节点 / title / placeholder / aria-label 四类属性的批量替换
 * 语言包路径：/i18n/{locale}.json
 * 用法：
 *   I18n.t('key')             — 翻译
 *   I18n.t('key', {n: 1})    — 带参数翻译（支持 {n} 占位符）
 *   I18n.switch('en')         — 切换语言（自动加载 + 持久化）
 *   I18n.apply()              — 手动触发 DOM 替换（动态插入内容后调用）
 */
(function () {
    'use strict';

    var STORAGE_KEY = 'sc-locale';
    var DEFAULT_LOCALE = 'zh-CN';

    var I18n = {
        /** 当前语言 */
        locale: DEFAULT_LOCALE,

        /** 已加载的语言包缓存 { 'en': {...}, 'zh-CN': {...} } */
        messages: {},

        /**
         * 加载语言包
         * @param {string} locale
         * @param {Function} [callback] callback(err)
         */
        load: function (locale, callback) {
            var self = this;
            if (this.messages[locale]) {
                if (callback) setTimeout(function () { callback(null); }, 0);
                return;
            }
            fetch('/i18n/' + locale + '.json?_v=' + (window._i18nVer || '1'))
                .then(function (r) {
                    if (!r.ok) throw new Error('HTTP ' + r.status);
                    return r.json();
                })
                .then(function (data) {
                    self.messages[locale] = data;
                    if (callback) callback(null);
                })
                .catch(function (e) {
                    console.warn('[I18n] Failed to load locale:', locale, e);
                    if (callback) callback(e);
                });
        },

        /**
         * 翻译 key，支持 {param} 占位符替换
         * @param {string} key
         * @param {Object} [params]
         * @returns {string}
         */
        t: function (key, params) {
            var pack = this.messages[this.locale] || {};
            var msg = pack[key];
            if (msg === undefined || msg === null) {
                // 回退：若不是默认语言，尝试用默认语言
                var defPack = this.messages[DEFAULT_LOCALE] || {};
                msg = defPack[key];
            }
            if (msg === undefined || msg === null) {
                if (window.__i18nDev) console.warn('[I18n] Missing key:', key);
                return key;
            }
            if (params) {
                return String(msg).replace(/\{(\w+)\}/g, function (_, k) {
                    return params[k] !== undefined ? params[k] : '{' + k + '}';
                });
            }
            return String(msg);
        },

        /**
         * 批量更新 DOM 中所有带 data-i18n* 属性的元素
         * 可在动态插入内容后调用以应用翻译
         * @param {Element} [root] 默认 document
         */
        apply: function (root) {
            var ctx = root || document;
            var self = this;

            // 1. 文本内容
            ctx.querySelectorAll('[data-i18n]').forEach(function (el) {
                var key = el.getAttribute('data-i18n');
                var val = self.t(key);
                if (val !== key) el.textContent = val;
            });

            // 2. HTML 内容已移除（存在 XSS 风险，禁止直接赋值 innerHTML）

            // 3. placeholder 属性
            ctx.querySelectorAll('[data-i18n-placeholder]').forEach(function (el) {
                var key = el.getAttribute('data-i18n-placeholder');
                var val = self.t(key);
                if (val !== key) el.placeholder = val;
            });

            // 4. title 属性
            ctx.querySelectorAll('[data-i18n-title]').forEach(function (el) {
                var key = el.getAttribute('data-i18n-title');
                var val = self.t(key);
                if (val !== key) el.title = val;
            });

            // 5. aria-label 属性
            ctx.querySelectorAll('[data-i18n-aria]').forEach(function (el) {
                var key = el.getAttribute('data-i18n-aria');
                var val = self.t(key);
                if (val !== key) el.setAttribute('aria-label', val);
            });
        },

        /**
         * 切换语言：加载语言包 → 更新 locale → 应用 DOM → 持久化
         * @param {string} locale 目标语言，如 'en' / 'zh-CN'
         */
        switch: function (locale) {
            var self = this;
            if (locale === this.locale) return;

            var doSwitch = function () {
                self.locale = locale;
                self.apply();
                localStorage.setItem(STORAGE_KEY, locale);
                document.documentElement.lang = locale;
                // 通知外部模块语言已切换
                document.dispatchEvent(new CustomEvent('i18n:switched', { detail: { locale: locale } }));
            };

            if (this.messages[locale]) {
                doSwitch();
            } else {
                this.load(locale, function (err) {
                    if (!err) doSwitch();
                    else console.error('[I18n] Cannot switch to', locale);
                });
            }
        },

        /**
         * 初始化：读取 localStorage → 若非默认语言则加载并应用
         */
        init: function () {
            var self = this;
            var saved = localStorage.getItem(STORAGE_KEY) || DEFAULT_LOCALE;
            this.locale = saved;
            document.documentElement.lang = saved;

            if (saved === DEFAULT_LOCALE) {
                // 默认语言：HTML 已是中文，预加载 zh-CN 包（供切换回来时使用）
                this.load(DEFAULT_LOCALE, function () { /* 静默预加载 */ });
                return;
            }
            // 非默认语言：加载并应用
            this.load(saved, function (err) {
                if (!err) self.apply();
            });
        },

        /**
         * 获取当前语言
         * @returns {string}
         */
        getLocale: function () {
            return this.locale;
        }
    };

    window.I18n = I18n;

    // DOM 就绪后自动初始化
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', function () { I18n.init(); });
    } else {
        I18n.init();
    }
})();
