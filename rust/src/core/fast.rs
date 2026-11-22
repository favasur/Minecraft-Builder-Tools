//! Numerical alternatives to the mirrored math, used by the analysis in `OPTIMIZATIONS.md`.
//!
//! The Java mod builds its ellipse from an 8192-entry trapezoid table of the arc-length integrand
//! and then binary-searches that table once per voussoir boundary. The same boundaries can be found
//! with a fixed-order Gauss–Legendre quadrature and a Newton iteration, which needs a few hundred
//! integrand evaluations instead of ~8192 plus a search per boundary. Both produce the same curve;
//! the tests below pin that the results agree before any timing claim is made.

/// Ellipse arc-length integrand `ds/dt = sqrt(a² sin²t + b² cos²t)`.
#[inline]
pub fn speed(a: f64, b: f64, t: f64) -> f64 {
    let s = t.sin();
    let c = t.cos();
    (a * a * s * s + b * b * c * c).sqrt()
}

/// Gauss–Legendre nodes and weights on `[-1, 1]` (Newton on the Legendre polynomial).
pub fn gauss_legendre(n: usize) -> (Vec<f64>, Vec<f64>) {
    let mut nodes = vec![0.0; n];
    let mut weights = vec![0.0; n];
    for i in 0..n {
        // Chebyshev starting guess, then Newton to the root.
        let mut x = (std::f64::consts::PI * (i as f64 + 0.75) / (n as f64 + 0.5)).cos();
        for _ in 0..20 {
            let (p, dp) = legendre(n, x);
            let dx = p / dp;
            x -= dx;
            if dx.abs() < 1.0e-15 {
                break;
            }
        }
        let (_, dp) = legendre(n, x);
        nodes[i] = x;
        weights[i] = 2.0 / ((1.0 - x * x) * dp * dp);
    }
    (nodes, weights)
}

/// `(P_n(x), P_n'(x))` by the three-term recurrence.
fn legendre(n: usize, x: f64) -> (f64, f64) {
    let mut p0 = 1.0;
    let mut p1 = x;
    if n == 0 {
        return (p0, 0.0);
    }
    for k in 2..=n {
        let kf = k as f64;
        let p2 = ((2.0 * kf - 1.0) * x * p1 - (kf - 1.0) * p0) / kf;
        p0 = p1;
        p1 = p2;
    }
    let dp = n as f64 * (x * p1 - p0) / (x * x - 1.0);
    (p1, dp)
}

/// One Gauss–Legendre panel over `[lo, hi]`.
fn panel(a: f64, b: f64, lo: f64, hi: f64, nodes: &[f64], weights: &[f64]) -> f64 {
    let half = (hi - lo) / 2.0;
    let mid = (lo + hi) / 2.0;
    nodes
        .iter()
        .zip(weights)
        .map(|(x, w)| w * speed(a, b, mid + half * x))
        .sum::<f64>()
        * half
}

/// Panels per full turn: the integrand is analytic but spans 2π, so a single Gauss rule over the
/// whole period is under-resolved. Panels of π/4 keep every rule local and the result exact.
fn panels(span: f64) -> usize {
    ((span / (std::f64::consts::PI / 4.0)).ceil() as usize).max(1)
}

/// Arc length of the ellipse over `[t0, t1]`, composite Gauss–Legendre.
pub fn arc_length(a: f64, b: f64, t0: f64, t1: f64, nodes: &[f64], weights: &[f64]) -> f64 {
    let segs = panels(t1 - t0);
    let h = (t1 - t0) / segs as f64;
    (0..segs)
        .map(|k| {
            let lo = t0 + k as f64 * h;
            panel(a, b, lo, lo + h, nodes, weights)
        })
        .sum()
}

/// The ellipse's full perimeter with `n`-point Gauss–Legendre quadrature.
pub fn perimeter(a: f64, b: f64, nodes: &[f64], weights: &[f64]) -> f64 {
    arc_length(a, b, 0.0, std::f64::consts::TAU, nodes, weights)
}

/// The `count + 1` boundary angles splitting the ring into equal arc-length steps.
///
/// The boundaries are monotone in `m`, so the walk advances through whole panels and only
/// integrates the single panel the boundary falls in, with a Newton correction on the exact
/// `ds/dt`. Cost is `O(count + panels)` integrand evaluations instead of the mirrored table's
/// `8192` samples plus one binary search per boundary.
pub fn thetas(a: f64, b: f64, count: usize, nodes: &[f64], weights: &[f64]) -> Vec<f64> {
    let total = perimeter(a, b, nodes, weights);
    let h = std::f64::consts::TAU / panels(std::f64::consts::TAU) as f64;
    let mut out = vec![0.0; count + 1];
    let mut k = 0usize;
    let mut acc = 0.0;

    for (m, slot) in out.iter_mut().enumerate().take(count).skip(1) {
        let target = total * m as f64 / count as f64;
        // Advance whole panels while the target is beyond them.
        loop {
            let lo = k as f64 * h;
            let hi = lo + h;
            if hi > std::f64::consts::TAU {
                break;
            }
            let seg = panel(a, b, lo, hi, nodes, weights);
            if acc + seg <= target {
                acc += seg;
                k += 1;
            } else {
                break;
            }
        }
        // Newton inside the remaining panel, starting from a linear guess.
        let lo = k as f64 * h;
        let mut t = (lo + (target - acc) / speed(a, b, lo)).min(lo + h);
        for _ in 0..8 {
            let s = acc + panel(a, b, lo, t, nodes, weights);
            let d = speed(a, b, t);
            let step = (s - target) / d;
            t -= step;
            if step.abs() < 1.0e-13 {
                break;
            }
        }
        *slot = t.clamp(0.0, std::f64::consts::TAU);
    }
    out[count] = std::f64::consts::TAU;
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::core::ellipse::build_ellipse;
    use crate::core::arch::Vec3;

    fn nodes16() -> (Vec<f64>, Vec<f64>) {
        gauss_legendre(16)
    }

    #[test]
    fn gauss_legendre_integrates_a_polynomial_exactly() {
        let (nodes, weights) = nodes16();
        // ∫_{-1}^{1} x^4 dx = 2/5, and 16-point Gauss is exact for degree <= 31.
        let got: f64 = nodes.iter().zip(&weights).map(|(x, w)| w * x.powi(4)).sum();
        assert!((got - 0.4).abs() < 1e-14);
    }

    #[test]
    fn circle_perimeter_is_exact_to_machine_precision() {
        let (nodes, weights) = nodes16();
        let got = perimeter(4.0, 4.0, &nodes, &weights);
        assert!((got - std::f64::consts::TAU * 4.0).abs() < 1e-12);
    }

    /// Dense Simpson reference for the perimeter, independent of both implementations.
    fn reference_perimeter(a: f64, b: f64) -> f64 {
        let n = 1_000_000;
        let h = std::f64::consts::TAU / n as f64;
        let mut sum = speed(a, b, 0.0) + speed(a, b, std::f64::consts::TAU);
        for i in 1..n {
            let w = if i % 2 == 0 { 2.0 } else { 4.0 };
            sum += w * speed(a, b, i as f64 * h);
        }
        sum * h / 3.0
    }

    #[test]
    fn perimeter_matches_a_dense_reference_and_the_mirrored_table() {
        let (nodes, weights) = nodes16();
        let fast = perimeter(8.0, 3.0, &nodes, &weights);
        let reference = reference_perimeter(8.0, 3.0);
        assert!(
            ((fast - reference) / reference).abs() < 1e-10,
            "gauss {fast} vs reference {reference}"
        );

        // The mirrored 8192-sample trapezoid table is the less accurate of the two.
        let ring = build_ellipse(
            Vec3::splat(0.0),
            Vec3::new(1.0, 0.0, 0.0),
            Vec3::new(0.0, 0.0, 1.0),
            8.0,
            3.0,
        );
        assert!(
            ((fast - ring.perimeter) / ring.perimeter).abs() < 1e-6,
            "gauss {fast} vs table {}",
            ring.perimeter
        );
    }

    #[test]
    fn newton_boundaries_land_on_equal_arc_length_steps() {
        let (nodes, weights) = nodes16();
        let a = 8.0;
        let b = 3.0;
        let count = 26;
        let ts = thetas(a, b, count, &nodes, &weights);
        let total = perimeter(a, b, &nodes, &weights);
        for (m, t) in ts.iter().enumerate().take(count).skip(1) {
            let s = arc_length(a, b, 0.0, *t, &nodes, &weights);
            let target = total * m as f64 / count as f64;
            assert!((s - target).abs() < 1e-9, "step {m}: {s} vs {target}");
        }
        // And they agree with the mirrored table's boundaries.
        let ring = build_ellipse(
            Vec3::splat(0.0),
            Vec3::new(1.0, 0.0, 0.0),
            Vec3::new(0.0, 0.0, 1.0),
            a,
            b,
        );
        let table_ts = thetas(a, b, ring.count, &nodes, &weights);
        for (i, (fast, table)) in table_ts.iter().zip(&ring.thetas).enumerate() {
            assert!((fast - table).abs() < 1e-6, "boundary {i}: {fast} vs {table}");
        }
    }

    #[test]
    fn arc_length_is_additive() {
        let (nodes, weights) = nodes16();
        let whole = arc_length(8.0, 3.0, 0.0, 2.0, &nodes, &weights);
        let split = arc_length(8.0, 3.0, 0.0, 0.7, &nodes, &weights)
            + arc_length(8.0, 3.0, 0.7, 2.0, &nodes, &weights);
        // Quadrature error differs per interval, so this bounds the split, not exact equality.
        assert!(((whole - split) / whole).abs() < 1e-9);
    }
}
