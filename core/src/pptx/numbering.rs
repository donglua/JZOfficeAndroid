enum Numeral {
    Decimal,
    Alphabetic,
    Roman,
}

pub(super) fn format(number: u32, scheme: &str) -> Option<String> {
    let (numeral, uppercase, prefix, suffix) = match scheme {
        "arabicPeriod" => (Numeral::Decimal, false, "", ". "),
        "arabicParenR" => (Numeral::Decimal, false, "", ") "),
        "arabicParenBoth" => (Numeral::Decimal, false, "(", ") "),
        "arabicPlain" => (Numeral::Decimal, false, "", " "),
        "alphaLcPeriod" => (Numeral::Alphabetic, false, "", ". "),
        "alphaUcPeriod" => (Numeral::Alphabetic, true, "", ". "),
        "alphaLcParenR" => (Numeral::Alphabetic, false, "", ") "),
        "alphaUcParenR" => (Numeral::Alphabetic, true, "", ") "),
        "alphaLcParenBoth" => (Numeral::Alphabetic, false, "(", ") "),
        "alphaUcParenBoth" => (Numeral::Alphabetic, true, "(", ") "),
        "romanLcPeriod" => (Numeral::Roman, false, "", ". "),
        "romanUcPeriod" => (Numeral::Roman, true, "", ". "),
        "romanLcParenR" => (Numeral::Roman, false, "", ") "),
        "romanUcParenR" => (Numeral::Roman, true, "", ") "),
        "romanLcParenBoth" => (Numeral::Roman, false, "(", ") "),
        "romanUcParenBoth" => (Numeral::Roman, true, "(", ") "),
        _ => return None,
    };
    let mut label = match numeral {
        Numeral::Decimal => number.to_string(),
        Numeral::Alphabetic => {
            let index = number.checked_sub(1)?;
            let letter = char::from_u32(u32::from(b'a') + index % 26)?;
            letter
                .to_string()
                .repeat(usize::try_from(index / 26 + 1).ok()?)
        }
        Numeral::Roman => roman(number)?,
    };
    if uppercase {
        label.make_ascii_uppercase();
    }
    Some(format!("{prefix}{label}{suffix}"))
}

fn roman(mut number: u32) -> Option<String> {
    if !(1..=3999).contains(&number) {
        return None;
    }
    let mut label = String::new();
    for (value, symbol) in [
        (1000, "m"),
        (900, "cm"),
        (500, "d"),
        (400, "cd"),
        (100, "c"),
        (90, "xc"),
        (50, "l"),
        (40, "xl"),
        (10, "x"),
        (9, "ix"),
        (5, "v"),
        (4, "iv"),
        (1, "i"),
    ] {
        while number >= value {
            label.push_str(symbol);
            number -= value;
        }
    }
    Some(label)
}
