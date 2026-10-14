import importlib.util,pathlib,unittest
spec=importlib.util.spec_from_file_location('ops_metrics',str(pathlib.Path(__file__).with_name('metrics.py')))
metrics=importlib.util.module_from_spec(spec)

class MetricsTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls): spec.loader.exec_module(metrics)
    def test_only_allowlisted_aggregate_sql_names_and_numeric_values_are_exposed(self):
        rendered=metrics.render_sql_rows('wallet_active_oldest_seconds\t700\nimage_pending\t2\napi_key\tsecret-value\n',True)
        self.assertIn('bobo_wallet_active_oldest_seconds 700',rendered)
        self.assertIn('bobo_image_pending 2',rendered)
        self.assertNotIn('secret',rendered)
        self.assertNotIn('api_key',rendered)
    def test_collection_failure_is_visible_without_raw_error_or_fabricated_zero_backlog(self):
        rendered=metrics.render_sql_rows('',False)
        self.assertIn('bobo_ops_collection_success{source="mysql"} 0',rendered)
        self.assertNotIn('bobo_image_pending',rendered)
    def test_container_names_are_escaped_and_only_numbers_emitted(self):
        value=metrics.render_containers([{'name':'nacos','memoryPercent':93.5,'restarts':2,'running':True}])
        self.assertIn('bobo_container_memory_percent{container="nacos"} 93.5',value)
        self.assertIn('bobo_container_restart_count{container="nacos"} 2',value)
    def test_non_numeric_sql_field_is_rejected_not_serialized(self):
        value=metrics.render_sql_rows('image_pending\tpassword=private\n',True)
        self.assertNotIn('password',value)
        self.assertIn('bobo_ops_collection_success{source="mysql"} 0',value)

if __name__=='__main__':unittest.main()
