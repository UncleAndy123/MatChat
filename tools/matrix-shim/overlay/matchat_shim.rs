// MatChat FFI overlay for matrix-sdk-ffi.
//
// Copied into matrix-rust-sdk/bindings/matrix-sdk-ffi/src/ by
// tools/matrix-shim/build-aar.sh, which also ensures `mod matchat_shim;` is
// declared in that crate's lib.rs. It adds MatChat-only methods to the existing
// FFI `Client` object (docs/VOICE.md §4.1 — the E2EE-call to-device primitive
// the upstream binding does not expose).
//
// SPIKE SCOPE: only `matchat_shim_version` is live. It exists purely to prove the
// pipeline — patch -> cargo-ndk cross-compile -> uniffi codegen -> .aar -> the
// method is callable from Kotlin as `client.matchatShimVersion()`. The real
// to-device methods are sketched (commented) at the bottom as the next
// increment; keeping them out keeps the spike's build green regardless of the
// exact matrix-sdk crypto API surface, which is confirmed against the pinned
// source only once the pipeline itself is proven.
//
// EXPORT MECHANISM: this assumes uniffi accepts a second exported `impl Client`
// block in a separate module of the defining crate. If the build rejects that,
// the fallback (documented in README.md) is to inline `matchat_shim_version`
// into the existing exported `impl Client` in client.rs. Discovering which of
// these holds is part of what the spike answers — match the attribute used by
// client.rs (`#[matrix_sdk_ffi_macros::export]` on recent trees, else
// `#[uniffi::export]`).

use crate::client::Client;

#[matrix_sdk_ffi_macros::export]
impl Client {
    /// Spike probe. Returns a constant so nothing but the FFI plumbing is under
    /// test: if Kotlin can call `client.matchatShimVersion()` and get this back,
    /// the whole patched-AAR pipeline works.
    pub fn matchat_shim_version(&self) -> String {
        "matchat-shim-0.1.0".to_string()
    }
}

// ---------------------------------------------------------------------------
// NEXT INCREMENT — real to-device primitive (NOT compiled in the spike).
//
// Reconcile the bodies against the pinned matrix-sdk source before enabling.
// `self.inner` is the `matrix_sdk::Client`; use its crypto/OlmMachine to encrypt
// to-device to specific devices (Element Call keys are Olm-encrypted), and its
// to-device event handler to surface incoming `io.element.call.encryption_keys`.
//
// #[matrix_sdk_ffi_macros::export]
// impl Client {
//     /// Olm-encrypt `content_json` and send it as a to-device event of
//     /// `event_type` to each (user_id, device_id) in `targets`.
//     pub async fn matchat_send_encrypted_to_device(
//         &self,
//         event_type: String,
//         targets: Vec<MatchatDeviceTarget>, // { user_id: String, device_id: String }
//         content_json: String,
//     ) -> Result<(), ClientError> { /* olm_machine().share/encrypt + send */ }
// }
//
// // Incoming keys: register a to-device handler on the inner client that forwards
// // decrypted `io.element.call.encryption_keys` events to a uniffi callback
// // interface, e.g.:
// //
// // #[matrix_sdk_ffi_macros::export(callback_interface)]
// // pub trait MatchatToDeviceListener: Send + Sync {
// //     fn on_encryption_keys(&self, sender: String, content_json: String);
// // }
// //
// // #[matrix_sdk_ffi_macros::export]
// // impl Client {
// //     pub fn matchat_subscribe_encryption_keys(
// //         &self,
// //         listener: Box<dyn MatchatToDeviceListener>,
// //     ) -> Arc<TaskHandle> { /* add_event_handler on the to-device stream */ }
// // }
// ---------------------------------------------------------------------------
