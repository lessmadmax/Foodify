package com.example.foodify.ar
import org.junit.Assert.*
import org.junit.Test
class ArCalibrationTest {
    private fun calibration()=(1..3).map {ArCalibration.Sample("c$it","밥","g$it","CALIBRATION",it*100.0,it*80.0)}
    @Test fun fitsDensityAndHeldOutError() {
        val data=calibration()+ArCalibration.Sample("e","밥","e","EVALUATION",400.0,320.0)
        val model=ArCalibration.fit("밥",data)
        assertEquals(.8,model.density,1e-9)
        val r=ArCalibration.evaluate("밥",data,model)
        assertEquals(0.0,r["maeGrams"] as Double,1e-9)
    }
    @Test fun repeatedFramesHaveOneGroupWeight() {
        val c=calibration();assertEquals(ArCalibration.fit("밥",c).density,ArCalibration.fit("밥",c+List(20){c.first().copy(id="repeat$it")}).density,1e-9)
    }
    @Test(expected=IllegalArgumentException::class) fun rejectsSplitLeakage() { ArCalibration.fit("밥",calibration()+calibration().first().copy(id="e",split="EVALUATION")) }
    @Test(expected=IllegalArgumentException::class) fun needsThreeDistinctGroups() { ArCalibration.fit("밥",calibration().take(2)) }
    @Test fun failedCaptureCountsInCoverage() {
        val c=calibration();val model=ArCalibration.fit("밥",c)
        val result=ArCalibration.evaluate("밥",c+listOf(ArCalibration.Sample("e1","밥","e1","EVALUATION",100.0,80.0),ArCalibration.Sample("e2","밥","e2","EVALUATION",null,80.0)),model)
        assertEquals(.5,result["resultCoverage"] as Double,1e-9)
        assertEquals(1,result["validGroups"])
    }
}
