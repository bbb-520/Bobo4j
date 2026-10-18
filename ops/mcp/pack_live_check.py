"""Package only a private compiled smoke class and its exact test-only dependencies."""
import os
import sys
import zipfile
import xml.etree.ElementTree as ET
from pathlib import Path


def main():
    build = Path(sys.argv[1]).resolve()
    output = Path(sys.argv[2]).resolve()
    module = build / 'agent-orchestrator-service'
    report = module / 'target/surefire-reports/TEST-com.bbb.exercise.agentdemo.orchestrator.execution.McpPublicDocumentationLiveTest.xml'
    properties = {p.get('name'): p.get('value') for p in ET.parse(report).getroot().findall('./properties/property')}
    dependencies = [Path(p) for p in properties['java.class.path'].split(os.pathsep)
                    if Path(p).suffix == '.jar' and Path(p).name.startswith(
                        ('h2-', 'assertj-core-', 'junit-jupiter-api-', 'junit-platform-commons-', 'opentest4j-', 'apiguardian-api-'))]
    assert len(dependencies) >= 3 and all(p.is_file() for p in dependencies)
    classes = list((module / 'target/test-classes/com/bbb/exercise/agentdemo/orchestrator/execution').glob('McpPublicDocumentationLiveTest*.class'))
    assert classes
    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output, 'w', zipfile.ZIP_DEFLATED) as archive:
        for dependency in dependencies:
            archive.write(dependency, 'lib/' + dependency.name)
        for source in classes:
            archive.write(source, 'classes/com/bbb/exercise/agentdemo/orchestrator/execution/' + source.name)
    print('Private live check packed:', len(classes), 'classes,', len(dependencies), 'test dependencies')


if __name__ == '__main__':
    main()
