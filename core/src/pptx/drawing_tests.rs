use super::{element_transform, gradient_points, inverse, prepare};
use crate::model::{Document, Element, GradientFill, Kind, Page};

#[test]
fn zero_height_line_rotates_and_flips_around_its_actual_center() {
    let line = Element {
        x: 80.0,
        y: 40.0,
        width: 40.0,
        rotation: 90.0,
        flip_h: true,
        ..Element::default()
    };
    assert_eq!(
        element_transform(&line),
        [0.0, -1.0, -1.0, 0.0, 100.0, 60.0]
    );
}

#[test]
fn nearly_axis_aligned_rotation_keeps_android_snapping() {
    let element = Element {
        x: 10.0,
        y: 20.0,
        width: 100.0,
        height: 50.0,
        rotation: 0.0005,
        ..Element::default()
    };
    assert_eq!(
        element_transform(&element),
        [1.0, 0.0, 0.0, 1.0, 10.0, 20.0]
    );
}

#[test]
fn inverse_preserves_shear_and_translation() {
    assert_eq!(
        inverse([1.0, 2.0, 3.0, 4.0, 5.0, 6.0]),
        Some([-2.0, 1.0, 1.5, -0.5, 1.0, -2.0])
    );
}

#[test]
fn inverse_rejects_singular_and_nearly_singular_affine_matrices() {
    assert!(inverse([1.0, 1.0, 1.0, 1.0, 0.0, 0.0]).is_none());
    assert!(inverse([0.000001, 0.000001, 0.0, 0.000001, 0.0, 0.0]).is_none());
    assert!(inverse([0.0, 0.0, 0.0, 1.0, 0.0, 0.0]).is_none());
}

#[test]
fn inverse_retains_small_diagonal_scales() {
    assert_eq!(
        inverse([0.000001, 0.0, 0.0, 0.000001, 0.0, 0.0]),
        Some([1000000.0, 0.0, 0.0, 1000000.0, 0.0, 0.0])
    );
}

#[test]
fn zero_area_scaled_gradient_uses_horizontal_direction() {
    assert_eq!(gradient_points(&gradient(), [0.0, 0.0]), [0.0; 4]);
}

#[test]
fn singular_background_transform_retains_solid_fill_and_warns() {
    let mut document = Document::new(Kind::PPTX);
    let page = Page {
        width: 720.0,
        height: 405.0,
        background: 0xffffffff,
        elements: Vec::new(),
    };
    let mut fill = gradient();
    fill.in_slide_space = true;
    let mut element = Element {
        width: 40.0,
        height: 20.0,
        fill: 0xff123456,
        fill_gradient: Some(fill),
        transform: [1.0, 1.0, 1.0, 1.0, 0.0, 0.0],
        ..Element::default()
    };

    assert!(prepare(&mut element, &page, &mut document));

    assert!(element.fill_gradient.is_none());
    assert_eq!(element.fill, 0xff123456);
    assert_eq!(
        document.warnings,
        ["Slide background fill has an invalid shape transform"]
    );
}

fn gradient() -> GradientFill {
    GradientFill {
        colors: vec![0xffff0000, 0xff0000ff],
        positions: vec![0.0, 1.0],
        points: [0.0; 4],
        transform: None,
        angle: 45.0,
        scaled: true,
        in_slide_space: false,
    }
}
