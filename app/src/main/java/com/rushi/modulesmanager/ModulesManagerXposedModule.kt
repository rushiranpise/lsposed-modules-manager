package com.rushi.modulesmanager

import io.github.libxposed.api.XposedModule

/**
 * Declares this app as an Xposed module so LSPosed Manager can install and update it.
 *
 * The scope is the app itself (`META-INF/xposed/scope.list`), so even when the module is
 * enabled the framework only loads this empty class into this process. Nothing is hooked.
 */
class ModulesManagerXposedModule : XposedModule()
