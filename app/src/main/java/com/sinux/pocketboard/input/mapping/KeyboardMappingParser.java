package com.sinux.pocketboard.input.mapping;

import android.content.Context;

import org.xmlpull.v1.XmlPullParser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class KeyboardMappingParser {

private static final String KEYBOARD_MAPPING_TAG =
        "KeyboardMapping";

private static final String KEY_TAG = "Key";
private static final String ADD_TAG = "Add";
private static final String ALT_TAG = "Alt";

private static final String CODE_ATTR = "code";
private static final String VALUE_ATTR = "value";
private static final String SHIFT_VALUE_ATTR =
        "shiftValue";

private static final String DOUBLE_PRESS_VALUE_ATTR =
        "doublePressValue";

private static final String DOUBLE_PRESS_SHIFT_VALUE_ATTR =
        "doublePressShiftValue";

private final XmlPullParser xpp;

public KeyboardMappingParser(
        Context context,
        String keyMappingFile,
        String modelName) {

    this(
            context.getResources().getXml(
                    context.getResources().getIdentifier(
                            "keyboard_mapping_"
                                    + keyMappingFile
                                    + modelName,
                            "xml",
                            context.getPackageName()
                    )
            )
    );
}

public KeyboardMappingParser(XmlPullParser xpp) {
    this.xpp = xpp;
}

public KeyboardMapping parseMapping()
        throws Exception {

    Map<Integer, KeyMapping> keyMappings =
            new HashMap<>();

    List<KeyMappingValue> currentKeyValues =
            new ArrayList<>();

    List<KeyMappingValue> currentKeyAltValues =
            new ArrayList<>();

    int currentKeyCode = 0;
    int currentDoublePressValue = 0;
    int currentDoublePressShiftValue = 0;

    while (xpp.getEventType()
            != XmlPullParser.END_DOCUMENT) {

        switch (xpp.getEventType()) {

            case XmlPullParser.START_TAG:

                if (KEY_TAG.equals(xpp.getName())) {

                    currentKeyCode = 0;

                    currentKeyValues.clear();
                    currentKeyAltValues.clear();

                    currentDoublePressValue = 0;
                    currentDoublePressShiftValue = 0;

                    for (int i = 0;
                         i < xpp.getAttributeCount();
                         i++) {

                        String attributeName =
                                xpp.getAttributeName(i);

                        String attributeValue =
                                xpp.getAttributeValue(i);

                        if (CODE_ATTR.equals(
                                attributeName)) {

                            currentKeyCode =
                                    Integer.parseInt(
                                            attributeValue
                                    );

                        } else if (
                                DOUBLE_PRESS_VALUE_ATTR
                                        .equals(attributeName)) {

                            currentDoublePressValue =
                                    attributeValue.codePointAt(0);

                        } else if (
                                DOUBLE_PRESS_SHIFT_VALUE_ATTR
                                        .equals(attributeName)) {

                            currentDoublePressShiftValue =
                                    attributeValue.codePointAt(0);
                        }
                    }

                    parseAndPutValue(
                            xpp,
                            currentKeyValues
                    );

                } else if (
                        ADD_TAG.equals(xpp.getName())) {

                    parseAndPutValue(
                            xpp,
                            currentKeyValues
                    );

                } else if (
                        ALT_TAG.equals(xpp.getName())) {

                    parseAndPutValue(
                            xpp,
                            currentKeyAltValues
                    );
                }

                break;

            case XmlPullParser.END_TAG:

                if (KEYBOARD_MAPPING_TAG.equals(
                        xpp.getName())) {

                    return new KeyboardMapping(
                            keyMappings
                    );

                } else if (
                        KEY_TAG.equals(xpp.getName())) {

                    keyMappings.put(
                            currentKeyCode,
                            new KeyMapping(
                                    currentKeyValues.toArray(
                                            new KeyMappingValue[0]
                                    ),
                                    currentKeyAltValues.toArray(
                                            new KeyMappingValue[0]
                                    ),
                                    currentDoublePressValue,
                                    currentDoublePressShiftValue
                            )
                    );
                }

                break;

            default:
                break;
        }

        xpp.next();
    }

    throw new IllegalStateException(
            "An error occurred during KeyboardMapping parsing"
    );
}

private static void parseAndPutValue(
        XmlPullParser xpp,
        List<KeyMappingValue> target) {

    int value = 0;
    int shiftValue = 0;

    for (int i = 0;
         i < xpp.getAttributeCount();
         i++) {

        String attributeName =
                xpp.getAttributeName(i);

        String attributeValue =
                xpp.getAttributeValue(i);

        if (VALUE_ATTR.equals(attributeName)) {

            value =
                    attributeValue.codePointAt(0);

        } else if (
                SHIFT_VALUE_ATTR.equals(attributeName)) {

            shiftValue =
                    attributeValue.codePointAt(0);
        }
    }

    target.add(
            new KeyMappingValue(
                    value,
                    shiftValue
            )
    );
}


}
