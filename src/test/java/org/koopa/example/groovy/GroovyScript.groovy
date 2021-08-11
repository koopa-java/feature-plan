package org.koopa.example.groovy

import org.koopa.example.groovy.impl.IGroovyScript

class GroovyScript implements IGroovyScript {
    @Override
    void execute() {
        println "execute success!"
    }
}
