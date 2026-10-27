//! Isolated cost of the cryptographic layer only (no sockets, no HTTP):
//! seal+open of 60 KiB media records, KK handshakes, password_scalar.
use kadri_pairing_poc::channel::{self, Initiator};
use kadri_pairing_poc::pairing::password_scalar;
use kadri_pairing_poc::record::{AppRecord, MAX_MEDIA};
use kadri_pairing_poc::Identity;
use std::time::Instant;

fn main() {
    let (k, h) = (Identity::generate().unwrap(), Identity::generate().unwrap());
    let reg = [7u8; 16];
    let connect = || {
        let (mut init, hs1) = Initiator::start(&k, &h.public, &reg).unwrap();
        let (hc, hs2) = channel::respond(&h, &hs1, |_| Some(k.public)).unwrap();
        (init.finish(&hs2).unwrap(), hc)
    };
    let (mut kc, mut hc) = connect();
    let rec = AppRecord::Media { transfer: 1, offset: 0, data: vec![0x5a; MAX_MEDIA] };
    let n = 4000;
    let t = Instant::now();
    for _ in 0..n {
        let r = hc.seal_app(&rec).unwrap();
        let _ = kc.open_app(&r).unwrap();
    }
    let s = t.elapsed().as_secs_f64();
    println!("records: {n} x 60 KiB seal+open (incl. record encode/decode copies): {:.0} MiB/s", (n * MAX_MEDIA) as f64 / 1048576.0 / s);
    let t = Instant::now();
    for _ in 0..500 {
        let _ = connect();
    }
    println!("Noise KK handshakes (both sides): {:.2} ms each", t.elapsed().as_secs_f64() * 1000.0 / 500.0);
    let t = Instant::now();
    for _ in 0..5 {
        let _ = password_scalar("40719283").unwrap();
    }
    println!("password_scalar (Argon2id m=8 MiB t=2 p=1): {:.0} ms each", t.elapsed().as_secs_f64() * 1000.0 / 5.0);
}
