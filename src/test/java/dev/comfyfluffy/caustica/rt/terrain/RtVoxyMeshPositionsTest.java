package dev.comfyfluffy.caustica.rt.terrain;

import it.unimi.dsi.fastutil.floats.FloatArrayList;
import me.cortex.voxy.api.LodQuad;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RtVoxyMeshPositionsTest {
    @Test void fractionalQuadsPreservePlaneBoundsAndAreaForEveryFace() {
        for (int face = 0; face < 6; face++) {
            int axis = face >> 1, au = axis == 2 ? 1 : 0, av = axis == 1 ? 1 : 2;
            int normal = axis == 0 ? 1 : axis == 1 ? 2 : 0;
            float[] origin = {2.25f,3.5f,4.75f};
            var quad = new LodQuad(face,0,0,0,0,false,au,av,normal,
                    origin[0],origin[1],origin[2],2.5f,3.25f,0,0);
            var positions = new FloatArrayList();
            RtVoxyMesh.appendPositions(positions,quad,origin[au],origin[av],origin[au]+2.5f,origin[av]+3.25f);
            assertEquals(12,positions.size());
            for (int point=0;point<4;point++) assertEquals(origin[normal],positions.getFloat(point*3+normal));
            float ux=positions.getFloat(3+au)-positions.getFloat(au);
            float vy=positions.getFloat(9+av)-positions.getFloat(av);
            assertEquals(8.125f,Math.abs(ux*vy));
            assertEquals(origin[au]+2.5f,positions.getFloat(6+au));
            assertEquals(origin[av]+3.25f,positions.getFloat(6+av));
        }
    }

    @Test void clippedFragmentKeepsFractionalNormalAndRequestedExtents() {
        var quad = new LodQuad(4,0,0,0,0,false,1,2,0,7.125f,0,0,32,32,0,0);
        var positions = new FloatArrayList();
        RtVoxyMesh.appendPositions(positions,quad,16,8.5f,20.25f,16);
        assertArrayEquals(new float[]{7.125f,16,8.5f,7.125f,20.25f,8.5f,
                7.125f,20.25f,16,7.125f,16,16},positions.toFloatArray());
    }
}
