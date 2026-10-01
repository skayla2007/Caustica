package dev.comfyfluffy.caustica.rt.terrain;

import it.unimi.dsi.fastutil.floats.FloatArrayList;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RtVoxyAttributesTest {
    @Test void quadRecordPreservesMaterialAndTintBitPatterns() {
        FloatArrayList material = new FloatArrayList();
        for (int face = 0; face < 6; face++) {
            RtVoxyAttributes.append(material, 65535, -1, 65535, face, 3, 511, 4, 8/15f);
            int offset = face*3;
            int header = Float.floatToRawIntBits(material.getFloat(offset));
            int tint = Float.floatToRawIntBits(material.getFloat(offset+1));
            assertEquals(65535, header & 65535);
            assertEquals(511, (header>>>16)&511);
            assertEquals(4, (header>>>25)&7);
            assertEquals(8, header>>>28);
            assertEquals(0xffffff, tint & 0xffffff);
            assertEquals(face, (tint>>>24)&7);
            assertEquals(3, (tint>>>27)&3);
            assertEquals(65535, Float.floatToRawIntBits(material.getFloat(offset+2)));
        }
        assertEquals(6*12, material.size()*4);
    }

    @Test void overflowingAtlasSlotCannotSilentlyCorruptGeometryMetadata() {
        assertThrows(IllegalArgumentException.class, () -> RtVoxyAttributes.append(new FloatArrayList(), 0,-1,0,0,0,512,0,0));
    }
}
