//
//  Generated file. Do not edit.
//

// clang-format off

#include "generated_plugin_registrant.h"

#include <quick_wlan/quick_wlan_plugin.h>

void fl_register_plugins(FlPluginRegistry* registry) {
    g_autoptr(FlPluginRegistrar)
    quick_wlan_registrar =
            fl_plugin_registry_get_registrar_for_plugin(registry, "QuickWlanPlugin");
    quick_wlan_plugin_register_with_registrar(quick_wlan_registrar);
}
