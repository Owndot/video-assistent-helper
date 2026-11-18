//! password_scalar known answers produced by vectors/argon2_kat.py with the
//! reference C Argon2 (argon2-cffi), independent of pakery/argon2-rs.
use kadri_pairing_poc::pairing::{password_scalar_bytes, PocArgon2, ARGON2_OUTPUT_LEN};
use pakery_crypto::ksf::Argon2Params;

#[test]
fn password_scalar_matches_reference_argon2() {
    let raw = std::fs::read_to_string(concat!(env!("CARGO_MANIFEST_DIR"), "/../vectors/password-scalar-kat.json")).unwrap();
    let doc: serde_json::Value = serde_json::from_str(&raw).unwrap();
    let p = &doc["profile"];
    assert_eq!(p["m_kib"], PocArgon2::M_COST);
    assert_eq!(p["t"], PocArgon2::T_COST);
    assert_eq!(p["p"], PocArgon2::P_COST);
    assert_eq!(p["output_len"], ARGON2_OUTPUT_LEN);
    let v = doc["vectors"].as_array().unwrap();
    assert!(v.len() >= 4);
    for e in v {
        let got = hex::encode(password_scalar_bytes(e["code"].as_str().unwrap()).unwrap());
        assert_eq!(got, e["scalar_be"].as_str().unwrap(), "{}", e["code"]);
    }
}
