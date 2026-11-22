use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::jstring;

use crate::{scripts, valid_username};

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_doro_terminal_shell_linux_RustSessionControl_nativeSystemServicesScript(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    to_jstring(env, scripts::system_services_script())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_doro_terminal_shell_linux_RustSessionControl_nativeSessionScript(
    mut env: JNIEnv,
    _class: JClass,
    display: JString,
    username: JString,
    gpu_mode: JString,
) -> jstring {
    let Some(display) = string_arg(&mut env, display, "display") else {
        return std::ptr::null_mut();
    };
    let Some(username) = string_arg(&mut env, username, "username") else {
        return std::ptr::null_mut();
    };
    let Some(gpu_mode) = string_arg(&mut env, gpu_mode, "gpuMode") else {
        return std::ptr::null_mut();
    };

    if !valid_username(&username) {
        let _ = env.throw_new(
            "java/lang/IllegalArgumentException",
            "username contains unsupported characters",
        );
        return std::ptr::null_mut();
    }

    to_jstring(
        env,
        scripts::session_script(&display, &username, scripts::GpuMode::parse(&gpu_mode)),
    )
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_doro_terminal_shell_linux_RustSessionControl_nativeStopScript(
    mut env: JNIEnv,
    _class: JClass,
    username: JString,
) -> jstring {
    let Some(username) = string_arg(&mut env, username, "username") else {
        return std::ptr::null_mut();
    };
    if !valid_username(&username) {
        let _ = env.throw_new(
            "java/lang/IllegalArgumentException",
            "username contains unsupported characters",
        );
        return std::ptr::null_mut();
    }
    to_jstring(env, scripts::stop_script(&username))
}

fn string_arg(env: &mut JNIEnv, value: JString, name: &str) -> Option<String> {
    match env.get_string(&value) {
        Ok(value) => Some(value.to_string_lossy().into_owned()),
        Err(error) => {
            let _ = env.throw_new(
                "java/lang/IllegalArgumentException",
                format!("invalid {name}: {error}"),
            );
            None
        }
    }
}

fn to_jstring(env: JNIEnv, value: String) -> jstring {
    env.new_string(value)
        .map(|value| value.into_raw())
        .unwrap_or(std::ptr::null_mut())
}
