use std::env;
use std::process::ExitCode;

use linuxhub_sessiond::{Capability, DISTRIBUTION, SessionPlan, SupervisorPolicy};

fn main() -> ExitCode {
    let mut args = env::args().skip(1);
    let display = args.next().unwrap_or_else(|| ":0".to_owned());
    let username = args.next().unwrap_or_else(|| "doro".to_owned());

    let plan = match SessionPlan::ubuntu_24_04_gnome46(&display, &username) {
        Ok(plan) => plan,
        Err(message) => {
            eprintln!("error: {message}");
            return ExitCode::from(2);
        }
    };

    println!("distribution={DISTRIBUTION}");
    println!("display={}", plan.display);
    println!("runtime_dir={}", plan.runtime_dir);
    println!("software_rendering={}", plan.software_rendering);
    println!(
        "supervisor_graceful_stop_ms={}",
        SupervisorPolicy::default().graceful_stop_ms
    );
    println!(
        "required_capabilities={:?}",
        Capability::REQUIRED_FOR_FIRST_DISPLAY
    );
    println!(
        "deferred_capabilities={:?}",
        Capability::DEFERRED_DEPENDENCIES
    );
    println!("\n--- launch script ---\n{}", plan.launch_script());
    println!(
        "--- capability probe ---\n{}",
        plan.capability_probe_script()
    );
    ExitCode::SUCCESS
}
