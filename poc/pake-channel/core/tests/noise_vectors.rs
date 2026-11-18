//! Cacophony test vector for the exact Noise protocol name used by the PoC,
//! run through the same snow build and resolver features as the PoC.
fn h(v: &serde_json::Value) -> Vec<u8> {
    hex::decode(v.as_str().unwrap()).unwrap()
}

#[test]
fn cacophony_noise_kk_25519_chachapoly_sha256() {
    let raw = std::fs::read_to_string(concat!(env!("CARGO_MANIFEST_DIR"), "/../vectors/noise-kk-cacophony.json")).unwrap();
    let doc: serde_json::Value = serde_json::from_str(&raw).unwrap();
    let vectors = doc["vectors"].as_array().unwrap();
    assert!(!vectors.is_empty());
    for v in vectors {
        let params: snow::params::NoiseParams = v["protocol_name"].as_str().unwrap().parse().unwrap();
        assert_eq!(v["protocol_name"], kadri_pairing_poc::NOISE_PARAMS);
        let (is, ie, irs, ip) = (h(&v["init_static"]), h(&v["init_ephemeral"]), h(&v["init_remote_static"]), h(&v["init_prologue"]));
        let (rs, re, rrs, rp) = (h(&v["resp_static"]), h(&v["resp_ephemeral"]), h(&v["resp_remote_static"]), h(&v["resp_prologue"]));
        let mut init = snow::Builder::new(params.clone()).local_private_key(&is).unwrap().remote_public_key(&irs).unwrap()
            .prologue(&ip).unwrap().fixed_ephemeral_key_for_testing_only(&ie).build_initiator().unwrap();
        let mut resp = snow::Builder::new(params).local_private_key(&rs).unwrap().remote_public_key(&rrs).unwrap()
            .prologue(&rp).unwrap().fixed_ephemeral_key_for_testing_only(&re).build_responder().unwrap();
        let msgs = v["messages"].as_array().unwrap();
        let (mut buf, mut pt) = (vec![0u8; 65535], vec![0u8; 65535]);
        // Two handshake messages, then transport messages alternating I->R, R->I.
        for (i, m) in msgs.iter().take(2).enumerate() {
            let (w, r) = if i % 2 == 0 { (&mut init, &mut resp) } else { (&mut resp, &mut init) };
            let n = w.write_message(&h(&m["payload"]), &mut buf).unwrap();
            assert_eq!(hex::encode(&buf[..n]), m["ciphertext"].as_str().unwrap(), "handshake msg {i}");
            let k = r.read_message(&buf[..n], &mut pt).unwrap();
            assert_eq!(&pt[..k], &h(&m["payload"])[..]);
        }
        assert_eq!(init.get_handshake_hash(), &h(&v["handshake_hash"])[..]);
        let (mut ti, mut tr) = (init.into_transport_mode().unwrap(), resp.into_transport_mode().unwrap());
        for (i, m) in msgs.iter().enumerate().skip(2) {
            let (w, r) = if i % 2 == 0 { (&mut ti, &mut tr) } else { (&mut tr, &mut ti) };
            let n = w.write_message(&h(&m["payload"]), &mut buf).unwrap();
            assert_eq!(hex::encode(&buf[..n]), m["ciphertext"].as_str().unwrap(), "transport msg {i}");
            let k = r.read_message(&buf[..n], &mut pt).unwrap();
            assert_eq!(&pt[..k], &h(&m["payload"])[..]);
        }
    }
}
