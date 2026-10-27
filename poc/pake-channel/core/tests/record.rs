//! Exact-length parsing of application records (input from the peer after
//! authentication; strict parsing still matters for a compromised peer).
use kadri_pairing_poc::record::{AppRecord, MAX_CONTROL, MAX_MEDIA};
use kadri_pairing_poc::Error;

#[test]
fn round_trip_all_kinds_at_their_limits() {
    for r in [
        AppRecord::Control(vec![]),
        AppRecord::Control(vec![b'x'; MAX_CONTROL]),
        AppRecord::Media { transfer: u32::MAX, offset: u64::MAX, data: vec![1] },
        AppRecord::Media { transfer: 1, offset: 0, data: vec![7; MAX_MEDIA] },
        AppRecord::End { transfer: 3, total: 0 },
    ] {
        assert_eq!(AppRecord::decode(&r.encode().unwrap()).unwrap(), r);
    }
    assert_eq!(AppRecord::Control(vec![0; MAX_CONTROL + 1]).encode().err(), Some(Error::TooLarge));
    assert_eq!(AppRecord::Media { transfer: 1, offset: 0, data: vec![] }.encode().err(), Some(Error::TooLarge));
}

#[test]
fn every_length_mismatch_is_rejected() {
    let media = AppRecord::Media { transfer: 1, offset: 5, data: vec![9; 100] }.encode().unwrap();
    let ctrl = AppRecord::Control(b"{}".to_vec()).encode().unwrap();
    let end = AppRecord::End { transfer: 1, total: 9 }.encode().unwrap();
    let mut cases: Vec<(&str, Vec<u8>)> = vec![
        ("empty", vec![]),
        ("unknown kind", vec![4, 0, 0]),
        ("media trailing byte", [&media[..], &[0]].concat()),
        ("media truncated", media[..media.len() - 1].to_vec()),
        ("media header only", media[..15].to_vec()),
        ("control trailing byte", [&ctrl[..], &[0]].concat()),
        ("control truncated", ctrl[..ctrl.len() - 1].to_vec()),
        ("end trailing byte", [&end[..], &[0]].concat()),
        ("end truncated", end[..12].to_vec()),
    ];
    // Declared lengths beyond the limits, with matching bodies.
    let mut big = vec![2, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0];
    big.extend_from_slice(&((MAX_MEDIA + 1) as u16).to_be_bytes());
    big.extend(std::iter::repeat(0).take(MAX_MEDIA + 1));
    cases.push(("media over 60 KiB", big));
    let mut zero = vec![2, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0];
    cases.push(("media zero length", std::mem::take(&mut zero)));
    let mut bigc = vec![1];
    bigc.extend_from_slice(&((MAX_CONTROL + 1) as u16).to_be_bytes());
    bigc.extend(std::iter::repeat(b'x').take(MAX_CONTROL + 1));
    cases.push(("control over 16 KiB", bigc));
    for (name, b) in cases {
        assert_eq!(AppRecord::decode(&b).err(), Some(Error::Malformed), "{name}");
    }
}
