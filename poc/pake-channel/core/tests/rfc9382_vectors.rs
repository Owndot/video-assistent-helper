//! RFC 9382 Appendix B (P-256) through the exact library entry points the PoC
//! uses. The vector file is shared with vectors/independent_check.py.
use p256::elliptic_curve::ff::PrimeField;
use pakery_crypto::{Spake2P256, SPAKE2_P256_M_COMPRESSED, SPAKE2_P256_N_COMPRESSED};
use pakery_spake2::{PartyA, PartyB};

fn scalar(h: &str) -> p256::Scalar {
    let b: [u8; 32] = hex::decode(h).unwrap().try_into().unwrap();
    p256::Scalar::from_repr(b.into()).unwrap()
}

#[test]
fn rfc9382_appendix_b_p256() {
    let raw = std::fs::read_to_string(concat!(env!("CARGO_MANIFEST_DIR"), "/../vectors/rfc9382-appendix-b.json")).unwrap();
    let doc: serde_json::Value = serde_json::from_str(&raw).unwrap();
    assert_eq!(hex::encode(SPAKE2_P256_M_COMPRESSED), doc["M"].as_str().unwrap());
    assert_eq!(hex::encode(SPAKE2_P256_N_COMPRESSED), doc["N"].as_str().unwrap());
    let vectors = doc["vectors"].as_array().unwrap();
    assert_eq!(vectors.len(), 4);
    for v in vectors {
        let s = |k: &str| v[k].as_str().unwrap().to_string();
        let (ida, idb) = (s("A"), s("B"));
        let (w, x, y) = (scalar(&s("w")), scalar(&s("x")), scalar(&s("y")));
        let (pa, sa) = PartyA::<Spake2P256>::start_with_scalar(&w, &x, ida.as_bytes(), idb.as_bytes(), b"").unwrap();
        let (pb, sb) = PartyB::<Spake2P256>::start_with_scalar(&w, &y, ida.as_bytes(), idb.as_bytes(), b"").unwrap();
        assert_eq!(hex::encode(&pa), s("pa"));
        assert_eq!(hex::encode(&pb), s("pb"));
        let oa = sa.finish(&pb).unwrap();
        let ob = sb.finish(&pa).unwrap();
        assert_eq!(hex::encode(oa.session_key.as_bytes()), s("ke"));
        assert_eq!(hex::encode(&oa.confirmation_mac), s("mac_a"));
        assert_eq!(hex::encode(&ob.confirmation_mac), s("mac_b"));
        oa.verify_peer_confirmation(&ob.confirmation_mac).unwrap();
        ob.verify_peer_confirmation(&oa.confirmation_mac).unwrap();
        // Role reversal: A must not accept its own confirmation as B's.
        assert!(oa.verify_peer_confirmation(&oa.confirmation_mac).is_err());
    }
}
