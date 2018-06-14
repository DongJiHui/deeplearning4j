package org.deeplearning4j.arbiter.optimize.serde.jackson;

import org.apache.commons.math3.distribution.*;
import org.nd4j.shade.jackson.core.JsonGenerator;
import org.nd4j.shade.jackson.databind.JsonSerializer;
import org.nd4j.shade.jackson.databind.SerializerProvider;

import java.io.IOException;

/**
 * Custom Jackson serializer for integer distributions
 *
 * @author Alex Black
 */
public class IntegerDistributionSerializer extends JsonSerializer<IntegerDistribution> {
    @Override
    public void serialize(IntegerDistribution d, JsonGenerator j, SerializerProvider serializerProvider)
                    throws IOException {
        Class<?> c = d.getClass();
        String s = c.getSimpleName();

        j.writeStartObject();
        j.writeStringField("distribution", s);

        if (c == BinomialDistribution.class) {
            BinomialDistribution bd = (BinomialDistribution) d;
            j.writeNumberField("trials", bd.getNumberOfTrials());
            j.writeNumberField("p", bd.getProbabilityOfSuccess());
        } else if (c == GeometricDistribution.class) {
            GeometricDistribution gd = (GeometricDistribution) d;
            j.writeNumberField("p", gd.getProbabilityOfSuccess());
        } else if (c == HypergeometricDistribution.class) {
            HypergeometricDistribution hd = (HypergeometricDistribution) d;
            j.writeNumberField("populationSize", hd.getPopulationSize());
            j.writeNumberField("numberOfSuccesses", hd.getNumberOfSuccesses());
            j.writeNumberField("sampleSize", hd.getSampleSize());
        } else if (c == PascalDistribution.class) {
            PascalDistribution pd = (PascalDistribution) d;
            j.writeNumberField("r", pd.getNumberOfSuccesses());
            j.writeNumberField("p", pd.getProbabilityOfSuccess());
        } else if (c == PoissonDistribution.class) {
            PoissonDistribution pd = (PoissonDistribution) d;
            j.writeNumberField("p", pd.getMean());
        } else if (c == UniformIntegerDistribution.class) {
            UniformIntegerDistribution ud = (UniformIntegerDistribution) d;
            j.writeNumberField("lower", ud.getSupportLowerBound());
            j.writeNumberField("upper", ud.getSupportUpperBound());
        } else if (c == ZipfDistribution.class) {
            ZipfDistribution zd = (ZipfDistribution) d;
            j.writeNumberField("numElements", zd.getNumberOfElements());
            j.writeNumberField("exponent", zd.getExponent());
        } else {
            throw new UnsupportedOperationException("Unknown or not supported IntegerDistribution: " + c);
        }

        j.writeEndObject();
    }
}
