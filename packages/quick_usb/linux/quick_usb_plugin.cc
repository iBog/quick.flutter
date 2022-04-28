#include "include/quick_usb/quick_usb_plugin.h"

#include <flutter_linux/flutter_linux.h>
#include <gtk/gtk.h>
#include <sys/utsname.h>

#include <cstring>

#define QUICK_USB_PLUGIN(obj) \
  (G_TYPE_CHECK_INSTANCE_CAST((obj), quick_usb_plugin_get_type(), \
                              QuickUsbPlugin))

struct _QuickUsbPlugin {
  GObject parent_instance;
};

G_DEFINE_TYPE(QuickUsbPlugin, quick_usb_plugin, g_object_get_type())

// Called when a method call is received from Flutter.
static void quick_usb_plugin_handle_method_call(
    QuickUsbPlugin* self,
    FlMethodCall* method_call) {
  g_autoptr(FlMethodResponse) response = nullptr;

  response = FL_METHOD_RESPONSE(fl_method_not_implemented_response_new());

  fl_method_call_respond(method_call, response, nullptr);
}

static void quick_usb_plugin_dispose(GObject* object) {
  G_OBJECT_CLASS(quick_usb_plugin_parent_class)->dispose(object);
}

static void quick_usb_plugin_class_init(QuickUsbPluginClass* klass) {
  G_OBJECT_CLASS(klass)->dispose = quick_usb_plugin_dispose;
}

static void quick_usb_plugin_init(QuickUsbPlugin* self) {}

static void method_call_cb(FlMethodChannel* channel, FlMethodCall* method_call,
                           gpointer user_data) {
  QuickUsbPlugin* plugin = QUICK_USB_PLUGIN(user_data);
  quick_usb_plugin_handle_method_call(plugin, method_call);
}

void quick_usb_plugin_register_with_registrar(FlPluginRegistrar* registrar) {
  QuickUsbPlugin* plugin = QUICK_USB_PLUGIN(
      g_object_new(quick_usb_plugin_get_type(), nullptr));

  g_autoptr(FlStandardMethodCodec) codec = fl_standard_method_codec_new();
  g_autoptr(FlMethodChannel) channel =
      fl_method_channel_new(fl_plugin_registrar_get_messenger(registrar),
                            "quick_usb",
                            FL_METHOD_CODEC(codec));
  fl_method_channel_set_method_call_handler(channel, method_call_cb,
                                            g_object_ref(plugin),
                                            g_object_unref);

  g_object_unref(plugin);
}