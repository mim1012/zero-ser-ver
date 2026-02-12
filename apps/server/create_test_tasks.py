"""
Supabase distributedTasks 테이블에 테스트 데이터 생성
"""
from app.database.supabase_client import get_supabase

def create_test_tasks():
    supabase = get_supabase()

    # 테스트 작업 10개 생성
    test_tasks = [
        {
            "taskType": "production",
            "targetNodeType": "worker",
            "productName": "갤럭시 S24 Ultra 512GB",
            "nvMid": "12345678",
            "keyword": "갤럭시",
            "productUrl": "https://msearch.shopping.naver.com/product/12345678",
            "variables": "{}",
            "status": "pending",
            "priority": 1
        },
        {
            "taskType": "production",
            "targetNodeType": "worker",
            "productName": "갤럭시 S24 256GB",
            "nvMid": "12345679",
            "keyword": "갤럭시",
            "productUrl": "https://msearch.shopping.naver.com/product/12345679",
            "variables": "{}",
            "status": "pending",
            "priority": 1
        },
        {
            "taskType": "production",
            "targetNodeType": "worker",
            "productName": "갤럭시 Z Fold5",
            "nvMid": "12345680",
            "keyword": "갤럭시폴드",
            "productUrl": "https://msearch.shopping.naver.com/product/12345680",
            "variables": "{}",
            "status": "pending",
            "priority": 2
        },
        {
            "taskType": "production",
            "targetNodeType": "worker",
            "productName": "아이폰 15 Pro Max",
            "nvMid": "12345681",
            "keyword": "아이폰",
            "productUrl": "https://msearch.shopping.naver.com/product/12345681",
            "variables": "{}",
            "status": "pending",
            "priority": 1
        },
        {
            "taskType": "production",
            "targetNodeType": "worker",
            "productName": "아이폰 15 Pro 256GB",
            "nvMid": "12345682",
            "keyword": "아이폰",
            "productUrl": "https://msearch.shopping.naver.com/product/12345682",
            "variables": "{}",
            "status": "pending",
            "priority": 1
        },
        {
            "taskType": "production",
            "targetNodeType": "worker",
            "productName": "갤럭시 버즈2 Pro",
            "nvMid": "12345683",
            "keyword": "갤럭시버즈",
            "productUrl": "https://msearch.shopping.naver.com/product/12345683",
            "variables": "{}",
            "status": "pending",
            "priority": 1
        },
        {
            "taskType": "production",
            "targetNodeType": "worker",
            "productName": "에어팟 프로 2세대",
            "nvMid": "12345684",
            "keyword": "에어팟",
            "productUrl": "https://msearch.shopping.naver.com/product/12345684",
            "variables": "{}",
            "status": "pending",
            "priority": 1
        },
        {
            "taskType": "production",
            "targetNodeType": "worker",
            "productName": "갤럭시 워치6 Classic",
            "nvMid": "12345685",
            "keyword": "갤럭시워치",
            "productUrl": "https://msearch.shopping.naver.com/product/12345685",
            "variables": "{}",
            "status": "pending",
            "priority": 1
        },
        {
            "taskType": "production",
            "targetNodeType": "worker",
            "productName": "애플워치 시리즈9",
            "nvMid": "12345686",
            "keyword": "애플워치",
            "productUrl": "https://msearch.shopping.naver.com/product/12345686",
            "variables": "{}",
            "status": "pending",
            "priority": 1
        },
        {
            "taskType": "production",
            "targetNodeType": "worker",
            "productName": "갤럭시 A54 5G",
            "nvMid": "12345687",
            "keyword": "갤럭시",
            "productUrl": "https://msearch.shopping.naver.com/product/12345687",
            "variables": "{}",
            "status": "pending",
            "priority": 1
        }
    ]

    try:
        # 테스트 데이터 삽입
        result = supabase.table('distributedTasks').insert(test_tasks).execute()
        print(f"✓ {len(result.data)}개의 테스트 작업이 생성되었습니다!")

        # 생성된 작업 확인
        pending_tasks = supabase.table('distributedTasks').select('*').eq('status', 'pending').execute()
        print(f"\n현재 pending 작업: {len(pending_tasks.data)}개")

        for task in pending_tasks.data[:3]:
            print(f"  - [{task['id']}] {task['productName']} (priority: {task['priority']})")

    except Exception as e:
        print(f"✗ 에러 발생: {str(e)}")

if __name__ == "__main__":
    create_test_tasks()
