package dev.comfyfluffy.caustica.rt.terrain;

import it.unimi.dsi.fastutil.floats.FloatArrayList;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RtVoxyAttributesTest {
    @Test void uniqueRecordsUseDirectStorageInsteadOfGrowingTheMesh() {
        var source = new FloatArrayList();
        for (int model = 0; model < 40; model++) RtVoxyAttributes.append(source,model,model,model,0,0,1,0,0);
        var packed = RtVoxyAttributes.pack(source.toFloatArray());
        assertEquals(1,packed.records().length);
        assertArrayEquals(source.toFloatArray(),packed.indices());
    }

    @Test void paletteRoundTripPreservesEveryAttributeAndDeduplicatesAcrossBuckets() {
        FloatArrayList source = new FloatArrayList();
        for (int repeat = 0; repeat < 20; repeat++) {
            for (int face = 0; face < 6; face++)
                RtVoxyAttributes.append(source,65535,0xfedcba,65535,face,3,511,4,1);
            RtVoxyAttributes.append(source,65535,0xfedcba,65534,0,3,511,4,1);
        }
        var compressed = RtVoxyAttributes.palette(source.toFloatArray());
        assertEquals(7*3,compressed.records().length);
        assertEquals(140,compressed.indices().length);
        for (int quad = 0; quad < compressed.indices().length; quad++) {
            int offset = Float.floatToRawIntBits(compressed.indices()[quad]) * 3;
            for (int word = 0; word < 3; word++) assertEquals(
                    Float.floatToRawIntBits(source.getFloat(quad*3+word)),
                    Float.floatToRawIntBits(compressed.records()[offset+word]));
        }
        assertTrue((compressed.indices().length+compressed.records().length)*4 < source.size()*4);
    }

    @Test void paletteRetainsDistinctTintEmissionAndMaterialValues() {
        FloatArrayList source = new FloatArrayList();
        RtVoxyAttributes.append(source,1,0x112233,9,0,0,2,0,0);
        RtVoxyAttributes.append(source,2,0x112233,9,0,0,2,0,0);
        RtVoxyAttributes.append(source,1,0x112234,9,0,0,2,0,0);
        RtVoxyAttributes.append(source,1,0x112233,9,0,0,2,0,1);
        assertEquals(12,RtVoxyAttributes.palette(source.toFloatArray()).records().length);
    }

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
